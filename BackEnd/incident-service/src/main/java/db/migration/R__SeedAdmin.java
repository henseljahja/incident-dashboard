package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Seeds the bootstrap administrator.
 *
 * <p><b>Java, not SQL</b>, and it lives in {@code src/main/java/db/migration} rather than
 * beside the {@code .sql} files: the password comes from the environment and must be
 * bcrypt-hashed, neither of which is expressible in Oracle SQL — and a {@code .java} file
 * dropped into {@code src/main/resources/db/migration} is never compiled, so Flyway would
 * copy it to the classpath as an inert text file and silently never run it. Flyway scans the
 * configured location {@code db/migration} for <em>classes</em> in the matching package,
 * which is what this file is.
 *
 * <p><b>Repeatable, not versioned.</b> A versioned {@code V6__} would be marked applied by
 * the warn-and-return path below (see {@link #migrate}), and nothing would ever re-attempt
 * it once {@code INCIDENT_ADMIN_PASSWORD} finally appeared.
 *
 * <p><b>And repeatable alone is not enough</b> — see {@link #getChecksum()}.
 */
public class R__SeedAdmin extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(R__SeedAdmin.class);

    /** The bootstrap username. Fixed, so it is a known target — hence the lockout in V4. */
    static final String ADMIN_USERNAME = "admin";

    /** The role bound in the same transaction as the user insert. Seeded by V5. */
    static final String ADMIN_ROLE_ID = "ADMIN";

    static final String PASSWORD_ENV_VAR = "INCIDENT_ADMIN_PASSWORD";

    /** Below this a bootstrap password is not worth hashing. */
    static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * Flyway re-runs a repeatable migration only when its checksum <em>changes</em>, and
     * {@code BaseJavaMigration.getChecksum()} returns {@code null} by default. null-vs-null
     * compares equal, so an unoverridden repeatable Java migration runs exactly once, ever,
     * and is skipped forever after — including the run where the operator finally sets the
     * environment variable. Overriding this is therefore load-bearing, not hygiene.
     *
     * <p>The checksum derives from the username and the <b>presence</b> of the password
     * variable, never its content: hashing the password itself would write a fingerprint of
     * a live credential into {@code flyway_schema_history}, where it is world-readable to
     * anyone with SELECT on the schema and survives every rotation. Presence is exactly the
     * bit that decides whether {@link #migrate} does anything, so the migration goes OUTDATED
     * the moment the variable appears — and not on every unrelated boot.
     */
    @Override
    public Integer getChecksum() {
        boolean passwordAvailable = !isBlank(System.getenv(PASSWORD_ENV_VAR));
        CRC32 crc = new CRC32();
        crc.update((ADMIN_USERNAME + '|' + ADMIN_ROLE_ID + '|' + passwordAvailable)
                .getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        String password = System.getenv(PASSWORD_ENV_VAR);

        if (isBlank(password)) {
            // WARN AND RETURN — never throw.
            //
            // Tests run this exact migration. A hard failure here aborts Flyway inside
            // AbstractOracleIT's bootstrap, which takes out every tier-2 IT in @BeforeAll on
            // a clean checkout. Production safety is not weakened by skipping: with no admin
            // row nobody can log in, which is the same outcome as refusing to boot — and
            // getChecksum() guarantees this migration is retried once the variable is set.
            log.warn("{} is not set - SKIPPING bootstrap admin seed. No account can log in "
                    + "until it is set and the application is restarted.", PASSWORD_ENV_VAR);
            return;
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            // Present but weak is an operator mistake, not an unconfigured environment.
            // Failing hard is correct here: quietly seeding a 6-character password on the one
            // account that holds `manage` is worse than not booting.
            throw new IllegalStateException(PASSWORD_ENV_VAR + " must be at least "
                    + MIN_PASSWORD_LENGTH + " characters; refusing to seed a weak bootstrap "
                    + "administrator password.");
        }

        String hash = new BCryptPasswordEncoder().encode(password);
        Connection connection = context.getConnection();

        // INSERT-ONLY: no WHEN MATCHED branch. If the admin already exists, this migration
        // does nothing at all — it must never overwrite a password the operator has since
        // rotated through /auth/change-password, and MERGE-with-update would silently roll it
        // back to the environment value on every deploy.
        int usersInserted = mergeInsert(connection, """
                MERGE INTO APP_USER t
                USING (SELECT ? AS USERNAME FROM DUAL) s
                   ON (t.USERNAME = s.USERNAME)
                 WHEN NOT MATCHED THEN
                      INSERT (USERNAME, PASSWORD_HASH, DISPLAY_NAME, IS_ACTIVE,
                              MUST_CHANGE_PASSWORD, PASSWORD_CHANGED_AT)
                      VALUES (s.USERNAME, ?, 'Bootstrap Administrator', 'Y', 'Y', SYSTIMESTAMP)
                """, ADMIN_USERNAME, hash);

        // Same transaction as the user insert, deliberately. An admin row with no
        // APP_USER_ROLE row logs in successfully to an empty menus[], a null defaultRoute and
        // a fail-closed 403 on every /api/** call: authenticated, and unable to do anything.
        mergeInsert(connection, """
                MERGE INTO APP_USER_ROLE t
                USING (SELECT ? AS USERNAME, ? AS ROLE_ID FROM DUAL) s
                   ON (t.USERNAME = s.USERNAME AND t.ROLE_ID = s.ROLE_ID)
                 WHEN NOT MATCHED THEN
                      INSERT (USERNAME, ROLE_ID) VALUES (s.USERNAME, s.ROLE_ID)
                """, ADMIN_USERNAME, ADMIN_ROLE_ID);

        if (usersInserted > 0) {
            log.info("Seeded bootstrap administrator '{}' with MUST_CHANGE_PASSWORD=Y.",
                    ADMIN_USERNAME);
        } else {
            log.info("Bootstrap administrator '{}' already exists; password left untouched.",
                    ADMIN_USERNAME);
        }
    }

    private static int mergeInsert(Connection connection, String sql, String... params)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
