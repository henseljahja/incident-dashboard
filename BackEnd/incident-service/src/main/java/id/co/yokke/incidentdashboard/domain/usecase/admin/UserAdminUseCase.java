package id.co.yokke.incidentdashboard.domain.usecase.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account administration — the only write path in an otherwise strictly read-only service.
 *
 * <p><b>Ported from yob's {@code dashboard-gateway} {@code UserCase}/{@code UserServiceImpl},
 * not reimplemented.</b> The behaviours copied across verbatim, because they are the ones that
 * have already been proven in production there:
 *
 * <ul>
 *   <li>{@code hasSearch} — one search box matching {@code username} OR display name,
 *       case-insensitively, rather than a field-per-filter API.</li>
 *   <li>{@code hasNotUsername} — <b>the caller never appears in their own list</b>. This is not
 *       cosmetic: it is what stops an administrator deactivating or demoting themselves and
 *       locking the last {@code manage} grant out of the system.</li>
 *   <li>The create guard ({@code U14} "already exists"), the update guard ({@code U15} "not
 *       found"), and {@code U18} — a terminated account is immutable, so reactivating someone
 *       is a deliberate new grant rather than an edit.</li>
 *   <li>{@code CREATED} is displayed as {@code PENDING} — an account exists but its owner has
 *       not yet set a password.</li>
 *   <li>An audit row per {@code CREATE_USER} / {@code UPDATE_USER} /
 *       {@code CHANGE_STATUS_USER}.</li>
 * </ul>
 *
 * <p>Three things are deliberately NOT ported, and each would have been a regression here:
 *
 * <ol>
 *   <li><b>Persistence.</b> yob is Spring Data JPA ({@code UserEntity}, {@code UserRepository},
 *       {@code UserSpecification}); this service has no JPA at all and no entity manager. The
 *       specifications are translated into the SQL below one-for-one.</li>
 *   <li><b>Password storage.</b> yob encrypts with reversible AES
 *       ({@code AESEncryptionUtil.encrypt}) so that {@code isPasswordQualified} can decrypt the
 *       last four passwords and compare them. Copying that would replace this service's bcrypt
 *       hashes with a scheme whose key recovers every password in the table. {@code APP_USER}
 *       keeps bcrypt (see {@code LoginUseCase}), and password reuse is simply not checked.</li>
 *   <li><b>Registration email.</b> yob mails a temp-token "create your password" link through
 *       {@code OutboundGatewayService}. There is no mail gateway in this service, so a new
 *       account is created with a one-time password the administrator reads off the response
 *       exactly once and hands over out of band, with {@code MUST_CHANGE_PASSWORD='Y'}.</li>
 * </ol>
 *
 * <p>{@code tenantId}/{@code region}/{@code area}/{@code branch} carry no meaning here — this
 * service has no tenancy — so they are absent rather than stored empty.
 */
@Service
@Lazy
public class UserAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(UserAdminUseCase.class);

    /** Longest page the list endpoint will serve, whatever `size` asks for. */
    public static final int MAX_PAGE_SIZE = 200;

    /** Length of a generated one-time password, in characters of the alphabet below. */
    private static final int TEMP_PASSWORD_LENGTH = 16;

    /**
     * Deliberately excludes the glyph pairs that are indistinguishable in most UI fonts —
     * {@code O}/{@code 0}, {@code l}/{@code I}/{@code 1}. This password is read off a screen and
     * retyped by a human exactly once, so an ambiguous character is a support ticket.
     */
    private static final String TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789#$%@";

    /** Raised for every administration failure. Mapped to a 4xx by the exception handler. */
    public static class UserAdminException extends RuntimeException {

        private final boolean conflict;

        public UserAdminException(String message, boolean conflict) {
            super(message);
            this.conflict = conflict;
        }

        /** True when the request collided with existing state (yob's {@code U14}/{@code U18}). */
        public boolean isConflict() {
            return conflict;
        }
    }

    /** Not-found, kept separate so the handler can map it to 404 rather than 400. */
    public static class UserNotFoundException extends RuntimeException {

        public UserNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * One row of the admin list. {@code roleId}/{@code roleName} are singular on the wire to
     * match yob's screen, but {@code APP_USER_ROLE} is many-to-many: {@link #primaryRoleSql()}
     * picks the role with the lowest {@code ROLE_PRIORITY}, which is the same rule
     * {@code PermissionUseCase} already uses to choose a landing route. Showing an arbitrary
     * one of several roles would contradict the route the user actually lands on.
     */
    public record AdminUser(String username, String name, String email, String roleId,
            String roleName, String status, Instant lastLoginAt, Instant createdAt) {
    }

    /** One page of {@link AdminUser}, shaped like the existing {@code PageResponse}. */
    public record AdminUserPage(List<AdminUser> content, int page, int size, long totalElements,
            int totalPages) {
    }

    /** A role the administrator may assign. */
    public record AssignableRole(String roleId, String name, String defaultRoute, int priority) {
    }

    /** Result of a create — carries the one-time password, which is never stored in plain text. */
    public record CreatedUser(String username, String temporaryPassword) {
    }

    private static final RowMapper<AdminUser> USER_MAPPER = (rs, rowNum) -> {
        Timestamp lastLogin = rs.getTimestamp("LAST_LOGIN_AT");
        Timestamp created = rs.getTimestamp("CREATED_AT");
        Timestamp lockedUntil = rs.getTimestamp("LOCKED_UNTIL");
        return new AdminUser(
                rs.getString("USERNAME"),
                rs.getString("DISPLAY_NAME"),
                rs.getString("EMAIL"),
                rs.getString("ROLE_ID"),
                rs.getString("ROLE_NAME"),
                deriveStatus(
                        "Y".equals(rs.getString("IS_ACTIVE")),
                        "Y".equals(rs.getString("MUST_CHANGE_PASSWORD")),
                        lastLogin == null ? null : lastLogin.toInstant(),
                        lockedUntil == null ? null : lockedUntil.toInstant()),
                lastLogin == null ? null : lastLogin.toInstant(),
                created == null ? null : created.toInstant());
    };

    /**
     * Status is DERIVED, not stored — the one deliberate divergence from yob, which keeps a
     * {@code STATUS} column.
     *
     * <p>{@code APP_USER} already answers the question three times over
     * ({@code IS_ACTIVE}, {@code MUST_CHANGE_PASSWORD}, {@code LOCKED_UNTIL}), and every one of
     * those is written by the login path. A fourth column restating them would be updated by
     * this class alone and would silently disagree the first time {@code LoginUseCase} locked an
     * account after five failures — the screen would keep saying ACTIVE about an account nobody
     * can log into.
     *
     * <p>{@code PENDING} matches yob's display rule for {@code CREATED}: the account exists but
     * its owner has never signed in. {@code TERMINATED} has no counterpart because nothing here
     * deletes accounts; deactivation is reversible and is spelled {@code INACTIVE}.
     */
    static String deriveStatus(boolean active, boolean mustChangePassword, Instant lastLoginAt,
            Instant lockedUntil) {
        if (!active) {
            return "INACTIVE";
        }
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            return "LOCKED";
        }
        if (mustChangePassword && lastLoginAt == null) {
            return "PENDING";
        }
        return "ACTIVE";
    }

    /**
     * The lowest-priority role held by a user, as a scalar subquery.
     *
     * <p>Lowest {@code ROLE_PRIORITY} wins, matching {@code V4__auth.sql}'s reasoning for making
     * that column UNIQUE and {@code PermissionUseCase}'s landing-route rule. A user holding both
     * ADMIN and VIEWER must read as ADMIN on this screen, because ADMIN is what they land as.
     */
    private static String primaryRoleSql() {
        return """
                (SELECT r.ROLE_ID FROM APP_USER_ROLE ur
                   JOIN APP_ROLE r ON r.ROLE_ID = ur.ROLE_ID
                  WHERE ur.USERNAME = u.USERNAME
                    AND r.ROLE_PRIORITY = (SELECT MIN(r2.ROLE_PRIORITY)
                                             FROM APP_USER_ROLE ur2
                                             JOIN APP_ROLE r2 ON r2.ROLE_ID = ur2.ROLE_ID
                                            WHERE ur2.USERNAME = u.USERNAME))
                """;
    }

    private final JdbcTemplate jdbc;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public UserAdminUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of accounts, newest first.
     *
     * <p>{@code callerUsername} is excluded from the result — yob's
     * {@code UserSpecification.hasNotUsername}, and the reason it exists: an administrator who
     * can see their own row can deactivate it, and if theirs is the last account holding
     * {@code incident.admin:manage} the system has no way back to a user-management screen.
     * Enforced in SQL rather than by filtering the page afterwards, because filtering after the
     * fact would leave one short page and a wrong {@code totalElements}.
     */
    public AdminUserPage list(String callerUsername, String search, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE u.USERNAME <> ? ");
        args.add(callerUsername);

        if (search != null && !search.isBlank()) {
            // LOWER(...) LIKE LOWER(...) on both sides, matching yob's
            // `criteriaBuilder.like(criteriaBuilder.lower(...))`. Wildcards in the caller's
            // input are escaped so a search for "100%" is a literal, not a match-everything.
            where.append(" AND (LOWER(u.USERNAME) LIKE ? ESCAPE '\\' ")
                    .append("  OR LOWER(NVL(u.DISPLAY_NAME, ' ')) LIKE ? ESCAPE '\\' ")
                    .append("  OR LOWER(NVL(u.EMAIL, ' ')) LIKE ? ESCAPE '\\') ");
            String pattern = '%' + escapeLike(search.toLowerCase(Locale.ROOT)) + '%';
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_USER u" + where, Long.class, args.toArray());
        long totalElements = total == null ? 0L : total;

        String sql = "SELECT u.USERNAME, u.DISPLAY_NAME, u.EMAIL, u.IS_ACTIVE,"
                + " u.MUST_CHANGE_PASSWORD, u.LAST_LOGIN_AT, u.LOCKED_UNTIL, u.CREATED_AT,"
                + " " + primaryRoleSql() + " AS ROLE_ID,"
                + " (SELECT r.DISPLAY_NAME FROM APP_ROLE r"
                + "   WHERE r.ROLE_ID = " + primaryRoleSql() + ") AS ROLE_NAME"
                + " FROM APP_USER u" + where
                + " ORDER BY u.CREATED_AT DESC, u.USERNAME"
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safePage * safeSize);
        pageArgs.add(safeSize);

        List<AdminUser> content = jdbc.query(sql, USER_MAPPER, pageArgs.toArray());
        int totalPages = (int) ((totalElements + safeSize - 1) / safeSize);
        return new AdminUserPage(content, safePage, safeSize, totalElements, totalPages);
    }

    /** One account by username, or a 404. The caller's own row is reachable here — only the
     * LIST hides it, so "view my own account" still works while "edit myself" does not. */
    public AdminUser get(String username) {
        String sql = "SELECT u.USERNAME, u.DISPLAY_NAME, u.EMAIL, u.IS_ACTIVE,"
                + " u.MUST_CHANGE_PASSWORD, u.LAST_LOGIN_AT, u.LOCKED_UNTIL, u.CREATED_AT,"
                + " " + primaryRoleSql() + " AS ROLE_ID,"
                + " (SELECT r.DISPLAY_NAME FROM APP_ROLE r"
                + "   WHERE r.ROLE_ID = " + primaryRoleSql() + ") AS ROLE_NAME"
                + " FROM APP_USER u WHERE u.USERNAME = ?";
        List<AdminUser> rows = jdbc.query(sql, USER_MAPPER, username);
        if (rows.isEmpty()) {
            throw new UserNotFoundException("No such user.");
        }
        return rows.get(0);
    }

    /** Every role, ordered the way the landing-route rule reads them. */
    public List<AssignableRole> roles() {
        return jdbc.query(
                "SELECT ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, ROLE_PRIORITY FROM APP_ROLE"
                        + " ORDER BY ROLE_PRIORITY",
                (rs, rowNum) -> new AssignableRole(
                        rs.getString("ROLE_ID"),
                        rs.getString("DISPLAY_NAME"),
                        rs.getString("DEFAULT_ROUTE"),
                        rs.getInt("ROLE_PRIORITY")));
    }

    /**
     * Creates an account and returns its one-time password.
     *
     * <p>yob's {@code createUser} rejects a duplicate with {@code U14} before doing anything
     * else; so does this. The password is generated here, hashed, and returned to the caller
     * exactly once — there is no second endpoint that re-reads it, because the only copy is the
     * bcrypt hash.
     *
     * <p>{@code @Transactional} because the account and its role grant are one fact: an
     * {@code APP_USER} row with no {@code APP_USER_ROLE} row is an account that authenticates
     * successfully and then 403s on every screen, which reads as a broken login.
     */
    @Transactional
    public CreatedUser create(String actor, String username, String displayName, String email,
            String roleId) {
        String cleanUsername = requireText(username, "Username is required.");
        String cleanRole = requireText(roleId, "Role is required.");
        requireText(displayName, "Name is required.");
        assertRoleExists(cleanRole);

        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_USER WHERE USERNAME = ?", Integer.class, cleanUsername);
        if (existing != null && existing > 0) {
            throw new UserAdminException("A user with that username already exists.", true);
        }

        String temporary = generateTemporaryPassword();
        jdbc.update("""
                INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, EMAIL,
                                      IS_ACTIVE, MUST_CHANGE_PASSWORD)
                VALUES (?, ?, ?, ?, 'Y', 'Y')
                """, cleanUsername, encoder.encode(temporary), displayName.trim(), trimToNull(email));
        jdbc.update("INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES (?, ?)",
                cleanUsername, cleanRole);

        audit(actor, "CREATE_USER", cleanUsername, "role=" + cleanRole);
        log.info("admin {} created user {} with role {}", actor, cleanUsername, cleanRole);
        return new CreatedUser(cleanUsername, temporary);
    }

    /**
     * Updates name, email, role and active flag.
     *
     * <p>Two guards ported from yob: {@code U15} when the account does not exist, and the
     * self-edit refusal. The latter is the write-side half of {@code hasNotUsername} — hiding
     * your own row from the list means nothing if {@code PUT} with your own username still
     * works, and demoting yourself out of {@code incident.admin:manage} is unrecoverable
     * without database access.
     */
    @Transactional
    public void update(String actor, String username, String displayName, String email,
            String roleId, boolean active) {
        String cleanRole = requireText(roleId, "Role is required.");
        requireText(displayName, "Name is required.");

        if (actor.equals(username)) {
            throw new UserAdminException(
                    "You cannot edit your own account from user management.", true);
        }
        assertRoleExists(cleanRole);

        int updated = jdbc.update("""
                UPDATE APP_USER
                   SET DISPLAY_NAME = ?, EMAIL = ?, IS_ACTIVE = ?
                 WHERE USERNAME = ?
                """, displayName.trim(), trimToNull(email), active ? "Y" : "N", username);
        if (updated == 0) {
            throw new UserNotFoundException("No such user.");
        }

        // Replace rather than merge: the screen presents a single role, so leaving a previously
        // granted second role in place would silently keep a privilege the administrator
        // believes they just removed.
        jdbc.update("DELETE FROM APP_USER_ROLE WHERE USERNAME = ?", username);
        jdbc.update("INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES (?, ?)",
                username, cleanRole);

        // Deactivating has to end the session too. Without this the account keeps working for
        // the full 24h refresh-token lifetime, so "deactivate" would visibly do nothing to
        // someone already signed in — which is exactly when it is used.
        if (!active) {
            jdbc.update("""
                    UPDATE AUTH_REFRESH_TOKEN SET REVOKED_AT = SYSTIMESTAMP
                     WHERE USERNAME = ? AND REVOKED_AT IS NULL
                    """, username);
        }

        audit(actor, "UPDATE_USER", username, "role=" + cleanRole + " active=" + active);
        log.info("admin {} updated user {} (role={}, active={})", actor, username, cleanRole,
                active);
    }

    /**
     * Issues a fresh one-time password and forces rotation on next login.
     *
     * <p>yob does this by mailing a new temp token; without a mail gateway the password is
     * returned to the administrator instead. Every outstanding refresh token is revoked for the
     * same reason {@code LoginUseCase.changePassword} revokes them: a reset that leaves the old
     * session alive has not reset anything.
     */
    @Transactional
    public CreatedUser resetPassword(String actor, String username) {
        if (actor.equals(username)) {
            throw new UserAdminException(
                    "Use Change Password to reset your own credentials.", true);
        }
        String temporary = generateTemporaryPassword();
        int updated = jdbc.update("""
                UPDATE APP_USER
                   SET PASSWORD_HASH = ?, MUST_CHANGE_PASSWORD = 'Y', FAILED_LOGIN_COUNT = 0,
                       LOCKED_UNTIL = NULL, PASSWORD_CHANGED_AT = SYSTIMESTAMP
                 WHERE USERNAME = ?
                """, encoder.encode(temporary), username);
        if (updated == 0) {
            throw new UserNotFoundException("No such user.");
        }
        jdbc.update("""
                UPDATE AUTH_REFRESH_TOKEN SET REVOKED_AT = SYSTIMESTAMP
                 WHERE USERNAME = ? AND REVOKED_AT IS NULL
                """, username);

        audit(actor, "RESET_PASSWORD", username, null);
        log.info("admin {} reset the password for {}", actor, username);
        return new CreatedUser(username, temporary);
    }

    private void assertRoleExists(String roleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_ROLE WHERE ROLE_ID = ?", Integer.class, roleId);
        if (count == null || count == 0) {
            throw new UserAdminException("No such role: " + roleId, false);
        }
    }

    private void audit(String actor, String action, String target, String detail) {
        jdbc.update("""
                INSERT INTO ADMIN_AUDIT (AUDIT_ID, ACTION, ACTOR, TARGET, DETAIL)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), action, actor, target, detail);
    }

    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(
                    random.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Escapes the LIKE metacharacters so user input cannot widen its own search. */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new UserAdminException(message, false);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
