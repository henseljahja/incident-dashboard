package id.co.yokke.incidentdashboard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The user-management surface, exercised through the real HTTP chain.
 *
 * <p>Every assertion below targets a behaviour ported from yob's {@code UserCase} — the guards
 * are the reason to copy that code rather than write new code, so they are the thing worth
 * pinning. A test that only proved "create then read back" would pass just as happily against
 * an implementation with none of them.
 *
 * <p>Run against MockMvc rather than the use case directly for the same reason
 * {@code AuthFlowIT} is: the invariant is the whole chain — {@code @RequirePrivilege} →
 * interceptor → grant set → HTTP status — and calling the bean would prove none of it.
 */
@AutoConfigureMockMvc
class UserAdminIT extends AbstractOracleIT {

    // Public test fixtures — see V7__seed_test_users.sql, test classpath only.
    private static final String ADMIN = "admin.test";
    private static final String ADMIN_PASSWORD = "AdminTest#2026";
    private static final String OPS = "ops.manager";
    private static final String OPS_PASSWORD = "OpsManager#2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String tokenFor(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
        }).get("token").toString();
    }

    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    @DisplayName("the menu V6 deleted is back, and it is back WITH its route and grants")
    void menuAndGrantsRestored() {
        Integer menu = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_MENU WHERE MENU_ID = 'incident.admin'", Integer.class);
        String route = jdbc.queryForObject(
                "SELECT ROUTE FROM APP_MENU WHERE MENU_ID = 'incident.admin'", String.class);
        Integer manage = jdbc.queryForObject("""
                SELECT COUNT(*) FROM APP_ROLE_PRIVILEGE
                 WHERE ROLE_ID = 'ADMIN' AND MENU_ID = 'incident.admin' AND PRIVILEGE = 'manage'
                """, Integer.class);

        assertThat(menu).isEqualTo(1);
        // V6's whole point: a menu row whose ROUTE the SPA does not serve is the bug. The
        // route asserted here must stay in step with the router entry in `src/router/index.ts`.
        assertThat(route).isEqualTo("/admin/users");
        assertThat(manage).isEqualTo(1);
    }

    @Test
    @DisplayName("a role without incident.admin:manage is refused — 403, not a filtered list")
    void opsManagerIsRefused() throws Exception {
        int status = mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + tokenFor(OPS, OPS_PASSWORD)))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("OPS_MANAGER holds incident.ops only; user administration must fail closed")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("create returns a one-time password that actually authenticates, exactly once")
    void createIssuesAWorkingTemporaryPassword() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        String created = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"created.user","name":"Created User",
                                 "email":"created@example.test","roleId":"VIEWER"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String temporary = asMap(created).get("temporaryPassword").toString();
        assertThat(temporary).as("the password exists nowhere else — the row stores only a hash")
                .isNotBlank();

        // The point of the assertion: the generated password is a REAL credential, not a
        // display string. A create that returns a password the account does not have hands the
        // administrator something to pass on that will simply not work.
        String login = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"created.user\",\"password\":\"" + temporary
                                + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(asMap(login).get("mustChangePassword"))
                .as("a handed-over password must be rotated on first use")
                .isEqualTo(true);

        String stored = jdbc.queryForObject(
                "SELECT PASSWORD_HASH FROM APP_USER WHERE USERNAME = 'created.user'",
                String.class);
        assertThat(stored)
                .as("bcrypt, not yob's reversible AES — the plain password is never recoverable")
                .startsWith("$2").doesNotContain(temporary);
    }

    @Test
    @DisplayName("a duplicate username is a 409, not a second row — yob's U14")
    void duplicateUsernameConflicts() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        String payload = """
                {"username":"dup.user","name":"Dup","email":"dup@example.test","roleId":"VIEWER"}
                """;
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(payload));

        int status = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_USER WHERE USERNAME = 'dup.user'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the administrator never sees their own row — yob's hasNotUsername")
    void callerIsExcludedFromTheirOwnList() throws Exception {
        String body = mockMvc.perform(get("/api/admin/users?size=200")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ADMIN_PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) asMap(body).get("content");

        assertThat(content).isNotEmpty();
        assertThat(content).extracting(row -> row.get("username"))
                .as("seeing your own row is what lets you deactivate the last manage grant")
                .doesNotContain(ADMIN);
    }

    @Test
    @DisplayName("editing your own account is refused, so the list exclusion cannot be bypassed")
    void selfEditIsRefused() throws Exception {
        int status = mockMvc.perform(put("/api/admin/users/" + ADMIN)
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed","email":"x@example.test",
                                 "roleId":"VIEWER","active":false}
                                """))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("hiding the row from the list means nothing if PUT on it still works")
                .isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT IS_ACTIVE FROM APP_USER WHERE USERNAME = ?", String.class, ADMIN))
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("deactivating a user revokes their live refresh tokens, not just the flag")
    void deactivationEndsTheSession() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"revoke.user","name":"Revoke","email":"r@example.test",
                         "roleId":"VIEWER"}
                        """));
        // A live refresh token, as if the account were signed in right now.
        jdbc.update("""
                INSERT INTO AUTH_REFRESH_TOKEN (JTI, USERNAME, EXPIRES_AT)
                VALUES ('it-revoke-jti', 'revoke.user', SYSTIMESTAMP + 1)
                """);

        mockMvc.perform(put("/api/admin/users/revoke.user")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Revoke","email":"r@example.test","roleId":"VIEWER",
                         "active":false}
                        """));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUTH_REFRESH_TOKEN"
                        + " WHERE USERNAME = 'revoke.user' AND REVOKED_AT IS NULL",
                Integer.class))
                .as("without this, deactivation does nothing for the 24h the cookie still lives"
                        + " — precisely the window it is used in")
                .isZero();
    }

    @Test
    @DisplayName("changing a role REPLACES it rather than accumulating grants")
    void roleChangeReplaces() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"role.user","name":"Role","email":"role@example.test",
                         "roleId":"VIEWER"}
                        """));

        mockMvc.perform(put("/api/admin/users/role.user")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Role","email":"role@example.test","roleId":"EXEC",
                         "active":true}
                        """));

        List<String> roles = jdbc.queryForList(
                "SELECT ROLE_ID FROM APP_USER_ROLE WHERE USERNAME = 'role.user'", String.class);
        assertThat(roles)
                .as("the screen shows ONE role; leaving the old grant behind keeps a privilege"
                        + " the administrator believes they just removed")
                .containsExactly("EXEC");
    }

    @Test
    @DisplayName("every administrative write leaves an audit row naming actor and target")
    void writesAreAudited() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"audit.user","name":"Audit","email":"a@example.test",
                         "roleId":"VIEWER"}
                        """));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT ACTION, ACTOR, TARGET FROM ADMIN_AUDIT WHERE TARGET = 'audit.user'
                """);

        assertThat(row.get("ACTION")).isEqualTo("CREATE_USER");
        assertThat(row.get("ACTOR")).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("search matches username, name and email, and its wildcards are literal")
    void searchIsScopedAndEscaped() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"searchable.person","name":"Findable Name",
                         "email":"findme@example.test","roleId":"VIEWER"}
                        """));

        for (String term : List.of("searchable", "Findable", "findme@")) {
            String body = mockMvc.perform(get("/api/admin/users?search=" + term)
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();
            assertThat(asMap(body).get("totalElements"))
                    .as("search term '%s' must match", term)
                    .isEqualTo(1);
        }

        // A LIKE metacharacter typed into the box is a literal, not a match-everything. Without
        // the ESCAPE clause this returns the whole table and reads as "search is broken".
        String wildcard = mockMvc.perform(get("/api/admin/users?search=%25")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(asMap(wildcard).get("totalElements")).isEqualTo(0);
    }

    @Test
    @DisplayName("status is derived from the login columns, so a lockout cannot read as ACTIVE")
    void statusFollowsTheLoginColumns() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"status.user","name":"Status","email":"s@example.test",
                         "roleId":"VIEWER"}
                        """));

        // Fresh account, never signed in: yob shows CREATED as PENDING.
        String pending = mockMvc.perform(get("/api/admin/users/status.user")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(asMap(pending).get("status")).isEqualTo("PENDING");

        // The case a stored STATUS column would get wrong: LoginUseCase writes LOCKED_UNTIL
        // without this class ever being involved.
        jdbc.update("UPDATE APP_USER SET LOCKED_UNTIL = SYSTIMESTAMP + 1"
                + " WHERE USERNAME = 'status.user'");
        String locked = mockMvc.perform(get("/api/admin/users/status.user")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(asMap(locked).get("status"))
                .as("a stored status column would still be saying ACTIVE here")
                .isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("an unknown username is a 404, distinct from a malformed request's 400")
    void unknownUserIs404() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);

        assertThat(mockMvc.perform(get("/api/admin/users/nobody.here")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        assertThat(mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"blank.name","name":"  ","roleId":"VIEWER"}
                                """))
                .andReturn().getResponse().getStatus())
                .as("a blank required field is the caller's mistake, not a missing row")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("every method a handler actually uses survives its CORS preflight")
    void writeMethodsSurvivePreflight() throws Exception {
        // The bug this pins shipped and was caught only by a browser: `allowedMethods` was
        // GET/POST/OPTIONS, which was correct while the API was read-only plus login. Adding
        // `PUT /api/admin/users/{username}` without adding PUT here fails the PREFLIGHT, so the
        // browser never sends the real request. Nothing server-side logs an error — MockMvc,
        // curl and every IT still pass, because none of them preflight. The SPA just says
        // "Failed to fetch", naming neither CORS nor the method.
        for (String method : new String[] {"GET", "POST", "PUT"}) {
            MvcResult result = mockMvc.perform(options("/api/admin/users/someone")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5175")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("preflight for %s must succeed", method)
                    .isEqualTo(200);
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                    .as("%s must be advertised, or the browser refuses to send the real request",
                            method)
                    .contains(method);
        }
    }
}
