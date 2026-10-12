package id.co.yokke.incidentdashboard.domain.usecase.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Credential verification, the failed-login lockout, and the refresh-token store.
 *
 * <h2>Every failure looks the same</h2>
 *
 * <p>Unknown username, wrong password, locked account and deactivated account all raise the
 * SAME {@link AuthenticationFailedException} with the same message. Distinguishing them is a
 * user-enumeration oracle: "account disabled" confirms the username exists, and against a
 * fixed bootstrap username like {@code admin} that is the entire first step of an attack.
 *
 * <h2>Boolean columns are CHAR(1)</h2>
 *
 * <p>{@code IS_ACTIVE} and {@code MUST_CHANGE_PASSWORD} are {@code CHAR(1)} {@code 'Y'}/{@code 'N'}
 * with CHECK constraints (see {@code V4__auth.sql}) — NOT {@code NUMBER(1)}. A {@code = 1}
 * comparison does not merely fail to match, it raises ORA-01722 on a character column; and a
 * truthiness test against the raw string would treat {@code 'N'} as set. Read them through
 * {@link #isYes} and nowhere else.
 *
 * <h2>Lockout</h2>
 *
 * <p>Without a counter, {@code /auth/token} is an unlimited guessing oracle against a known
 * username. Five consecutive failures set {@code LOCKED_UNTIL}; a success clears BOTH the
 * counter and the lock and stamps {@code LAST_LOGIN_AT}.
 */
@Service
@Lazy
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    /** Consecutive failures tolerated before the account is locked. */
    public static final int MAX_FAILED_LOGINS = 5;

    /** How long a lock lasts. Long enough to kill a guessing run, short enough to self-heal. */
    public static final int LOCK_MINUTES = 15;

    /**
     * The single failure raised by every unsuccessful login, whatever the real reason. The
     * reason is logged (server-side, without the password); the client is told nothing.
     */
    public static class AuthenticationFailedException extends RuntimeException {

        public AuthenticationFailedException(String message) {
            super(message);
        }
    }

    /** Outcome of a successful credential check. */
    public record LoginResult(String username, boolean mustChangePassword) {
    }

    private record UserRow(String username, String passwordHash, boolean active,
            boolean mustChangePassword, int failedCount, Instant lockedUntil) {
    }

    private static final RowMapper<UserRow> USER_MAPPER = (rs, rowNum) -> new UserRow(
            rs.getString("USERNAME"),
            rs.getString("PASSWORD_HASH"),
            isYes(rs.getString("IS_ACTIVE")),
            isYes(rs.getString("MUST_CHANGE_PASSWORD")),
            rs.getInt("FAILED_LOGIN_COUNT"),
            rs.getTimestamp("LOCKED_UNTIL") == null
                    ? null : rs.getTimestamp("LOCKED_UNTIL").toInstant());

    private static final String SELECT_USER = """
            SELECT USERNAME, PASSWORD_HASH, IS_ACTIVE, MUST_CHANGE_PASSWORD,
                   FAILED_LOGIN_COUNT, LOCKED_UNTIL
              FROM APP_USER
             WHERE USERNAME = ?
            """;

    private final JdbcTemplate jdbc;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** A real, well-formed hash of a value nobody knows. Used only to burn time. */
    private final String timingDecoyHash;

    public LoginUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.timingDecoyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * Verifies a username/password pair.
     *
     * <p>A user under forced rotation still authenticates successfully — the caller scopes the
     * token it issues. Refusing the login outright strands the bootstrap account, which is
     * seeded with {@code MUST_CHANGE_PASSWORD='Y'}, with no way back in.
     *
     * @throws AuthenticationFailedException on ANY failure, indistinguishably
     */
    public LoginResult authenticate(String username, String rawPassword) {
        if (isBlank(username) || isBlank(rawPassword)) {
            throw failure("blank credentials");
        }
        UserRow user = find(username);
        if (user == null) {
            // Still spend the bcrypt cost, against a hash of a random value generated at
            // startup: returning instantly for an unknown username while a known one takes
            // ~80ms is a timing oracle over the user list.
            encoder.matches(rawPassword, timingDecoyHash);
            throw failure("unknown username");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now())) {
            throw failure("account locked until " + user.lockedUntil());
        }
        if (!encoder.matches(rawPassword, user.passwordHash())) {
            registerFailure(user);
            throw failure("bad password");
        }
        if (!user.active()) {
            // Deliberately AFTER the password check and reported identically: an "inactive"
            // reply for a correct password is still an enumeration signal.
            throw failure("inactive account");
        }
        clearFailures(user.username());
        return new LoginResult(user.username(), user.mustChangePassword());
    }

    /**
     * Re-checks that an already-authenticated principal may still be issued an access token —
     * the {@code /auth/refresh-token} path.
     *
     * <p>Skipping this is how a deactivated or locked account keeps minting valid access tokens
     * for the full 24-hour refresh lifetime after being disabled. It also returns the CURRENT
     * {@code MUST_CHANGE_PASSWORD}, so a user under forced rotation cannot simply exchange
     * their refresh token for an unscoped one.
     */
    public LoginResult reauthorize(String username) {
        UserRow user = find(username);
        if (user == null || !user.active()
                || (user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now()))) {
            throw failure("refresh refused for '" + username + "'");
        }
        return new LoginResult(user.username(), user.mustChangePassword());
    }

    /**
     * Rotates a password. Verifies the current one first — an authenticated session is not
     * authority to set a new password without proving the old one, or a stolen access token
     * becomes permanent account takeover.
     *
     * <p>Clears {@code MUST_CHANGE_PASSWORD} and revokes every outstanding refresh token: the
     * point of a rotation is that credentials issued before it stop working.
     */
    public void changePassword(String username, String currentPassword, String newPassword) {
        if (isBlank(newPassword) || newPassword.length() < 12) {
            throw new IllegalArgumentException(
                    "New password must be at least 12 characters.");
        }
        UserRow user = find(username);
        if (user == null || !encoder.matches(currentPassword, user.passwordHash())) {
            throw failure("change-password with a bad current password");
        }
        jdbc.update("""
                UPDATE APP_USER
                   SET PASSWORD_HASH = ?, MUST_CHANGE_PASSWORD = 'N',
                       PASSWORD_CHANGED_AT = SYSTIMESTAMP,
                       FAILED_LOGIN_COUNT = 0, LOCKED_UNTIL = NULL
                 WHERE USERNAME = ?
                """, encoder.encode(newPassword), user.username());
        jdbc.update("""
                UPDATE AUTH_REFRESH_TOKEN SET REVOKED_AT = SYSTIMESTAMP
                 WHERE USERNAME = ? AND REVOKED_AT IS NULL
                """, user.username());
        log.info("Password rotated for '{}'; outstanding refresh tokens revoked.",
                user.username());
    }

    // ---------------------------------------------------------------- refresh-token store

    public void storeRefreshToken(String jti, String username, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO AUTH_REFRESH_TOKEN (JTI, USERNAME, EXPIRES_AT)
                VALUES (?, ?, ?)
                """, jti, username, Timestamp.from(expiresAt));
    }

    /**
     * A {@code jti} is usable only while it is present, unrevoked and unexpired IN THE STORE.
     * The signature alone is not enough: without this lookup logout and revocation are
     * silently impossible, since a signed token stays valid until it expires on its own.
     */
    public boolean isRefreshTokenLive(String jti) {
        Integer live = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AUTH_REFRESH_TOKEN
                 WHERE JTI = ? AND REVOKED_AT IS NULL AND EXPIRES_AT > SYSTIMESTAMP
                """, Integer.class, jti);
        return live != null && live > 0;
    }

    public void revokeRefreshToken(String jti) {
        jdbc.update("""
                UPDATE AUTH_REFRESH_TOKEN SET REVOKED_AT = SYSTIMESTAMP
                 WHERE JTI = ? AND REVOKED_AT IS NULL
                """, jti);
    }

    // ---------------------------------------------------------------------------- internals

    private UserRow find(String username) {
        List<UserRow> rows = jdbc.query(SELECT_USER, USER_MAPPER, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void registerFailure(UserRow user) {
        int attempts = user.failedCount() + 1;
        if (attempts >= MAX_FAILED_LOGINS) {
            jdbc.update("""
                    UPDATE APP_USER
                       SET FAILED_LOGIN_COUNT = ?,
                           LOCKED_UNTIL = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'MINUTE')
                     WHERE USERNAME = ?
                    """, attempts, LOCK_MINUTES, user.username());
            log.warn("Account '{}' locked for {} minutes after {} failed logins.",
                    user.username(), LOCK_MINUTES, attempts);
        } else {
            jdbc.update("UPDATE APP_USER SET FAILED_LOGIN_COUNT = ? WHERE USERNAME = ?",
                    attempts, user.username());
        }
    }

    private void clearFailures(String username) {
        jdbc.update("""
                UPDATE APP_USER
                   SET FAILED_LOGIN_COUNT = 0, LOCKED_UNTIL = NULL,
                       LAST_LOGIN_AT = SYSTIMESTAMP
                 WHERE USERNAME = ?
                """, username);
    }

    /**
     * Logs the real reason at DEBUG and returns the generic exception. The username is logged;
     * the password never is, here or anywhere else — see {@code AuthFilter} on why no request
     * body is logged at all.
     */
    private AuthenticationFailedException failure(String reason) {
        log.debug("Authentication failed: {}", reason);
        return new AuthenticationFailedException("Authentication failed.");
    }

    /** {@code CHAR(1)} 'Y'/'N', never numeric. See the class javadoc. */
    private static boolean isYes(String flag) {
        return flag != null && "Y".equalsIgnoreCase(flag.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
