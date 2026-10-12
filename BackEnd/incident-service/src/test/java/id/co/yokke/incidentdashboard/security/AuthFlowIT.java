package id.co.yokke.incidentdashboard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.PrivilegeInterceptor;
import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * End-to-end guard for Task 14: authentication, server-side authorization, and the error
 * surface. Every test here targets a defect that has actually shipped in systems built this
 * way, not a happy path.
 *
 * <h2>Every route below is now a real one</h2>
 *
 * <p>These assertions were originally written against test-classpath stub controllers
 * annotated <b>exactly</b> as the plan's route map specifies, because the invariant under test
 * is the whole chain (annotation → interceptor → grant set → HTTP status) and only a
 * registered handler exercises it. Asserting on {@code PermissionUseCase.grantsFor} alone
 * would prove that the rows in {@code V5} are what {@code V5} says, and nothing about whether
 * the interceptor is on the chain, whether {@code menu()} is really satisfied by ANY listed
 * menu, or whether the refusal is a 403.
 *
 * <p>Each owning task deleted the stub it replaced — a surviving stub fails this context with
 * an ambiguous mapping, loudly and locally. Task 16 did so for {@code /api/incidents} and
 * Task 19 for {@code /api/metrics/*}, so every route asserted below now runs against the real
 * {@code IncidentController}, {@code MetricsController} or {@code FreshnessController}, which
 * is the point of writing them this way. What remains in {@link RouteMapUnderTest} is
 * {@code /api/it-only/**}: deliberate defects, not routes.
 *
 * <p>A 404 is deliberately never asserted as success.
 */
@AutoConfigureMockMvc
class AuthFlowIT extends AbstractOracleIT {

    // Public test fixtures — see V7__seed_test_users.sql, test classpath only.
    private static final String ADMIN = "admin.test";
    private static final String ADMIN_PASSWORD = "AdminTest#2026";
    private static final String OPS = "ops.manager";
    private static final String OPS_PASSWORD = "OpsManager#2026";
    private static final String EXEC = "exec.user";
    private static final String EXEC_PASSWORD = "ExecUser#2026";
    private static final String WALLBOARD = "wallboard.user";
    private static final String WALLBOARD_PASSWORD = "Wallboard#2026";
    private static final String VIEWER = "viewer";
    private static final String VIEWER_PASSWORD = "Viewer#2026";
    private static final String INACTIVE = "inactive.user";
    private static final String INACTIVE_PASSWORD = "Inactive#2026";
    private static final String ROTATE = "rotate.me";
    private static final String ROTATE_PASSWORD = "RotateMe#2026";

    /** The bcrypt hash V7 seeds for {@code rotate.me}; restored after the rotation test. */
    private static final String ROTATE_SEEDED_HASH =
            "$2a$10$9lpCodpWLBs1XXNKlQ5AXOuudonOEqkt7IzJfVEk8E7XqcFYIkjZe";

    /** Any route in the map that is NOT reachable by a change-password-scoped token. */
    private static final String[] API_ROUTES = {
            "/api/freshness", "/api/incidents", "/api/metrics/kpi", "/api/metrics/trend",
            "/api/metrics/top-categories", "/api/metrics/matrix", "/api/metrics/sla-buckets",
            "/api/metrics/definitions"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------ structural wiring

    /**
     * An interceptor that is merely a {@code @Component} is INERT: the annotations, the test
     * names and the code review all still say the authorization layer is there. This asserts
     * the REGISTRATION structurally — it pulls the handler chain out of
     * {@code RequestMappingHandlerMapping} — so deleting {@code WebConfig.addInterceptors}
     * turns it red even though some other layer might still produce a 403 elsewhere.
     */
    @Test
    @DisplayName("PrivilegeInterceptor is actually on the /api/** handler chain")
    void theInterceptorIsActuallyRegistered() throws Exception {
        RequestMappingHandlerMapping mapping =
                context.getBean(RequestMappingHandlerMapping.class);

        HandlerExecutionChain apiChain =
                mapping.getHandler(new MockHttpServletRequest("GET", "/api/freshness"));
        assertThat(apiChain)
                .as("/api/freshness must resolve to a handler; a 404 would make every other "
                        + "assertion in this class vacuous")
                .isNotNull();
        assertThat(apiChain.getInterceptorList())
                .as("PrivilegeInterceptor must be REGISTERED, not merely a bean")
                .anyMatch(PrivilegeInterceptor.class::isInstance);

        // And the path pattern is real: /auth/** is authenticated by the filter, never
        // privilege-checked by the interceptor.
        HandlerExecutionChain authChain =
                mapping.getHandler(new MockHttpServletRequest("GET", "/auth/me"));
        assertThat(authChain).isNotNull();
        assertThat(authChain.getInterceptorList())
                .as("/auth/** is outside the interceptor's /api/** path pattern")
                .noneMatch(PrivilegeInterceptor.class::isInstance);
    }

    /**
     * Fail-closed. An {@code /api/**} handler with no {@link RequirePrivilege} is a bug —
     * someone added a route and forgot the annotation — and the open default is the one that
     * ships a hole quietly. Run as the ADMIN, who holds every grant in the system: if this
     * returned 200 it would be because the endpoint is open, not because the caller is
     * privileged.
     */
    @Test
    @DisplayName("an undecorated /api/** handler is refused even for the admin")
    void anUndecoratedApiHandlerIsRefused() throws Exception {
        String token = login(ADMIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/it-only/undecorated").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("fail-closed: no @RequirePrivilege means refused")
                        .isEqualTo(403));
    }

    /**
     * A cross-origin request carrying {@code Authorization} always triggers a preflight, and a
     * preflight carries NO {@code Authorization} header — it cannot, by specification. It is
     * also dispatched to Spring's internal {@code PreFlightHandler}, which is not a
     * {@code HandlerMethod} and so cannot carry an annotation, yet Spring retains the matched
     * interceptors on its chain.
     *
     * <p>Without {@code AuthFilter}'s OPTIONS short-circuit this is a 401; without
     * {@code PrivilegeInterceptor}'s non-{@code HandlerMethod} exemption it is a 403. Either
     * way every preflight fails, the entire SPA breaks, and the API stays perfectly functional
     * to {@code curl} — which is the confusing version of this bug.
     */
    @Test
    @DisplayName("a CORS preflight is neither authenticated nor privilege-checked")
    void aCorsPreflightIsNotAuthenticatedAndNotPrivilegeChecked() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/freshness")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5175")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("preflight must succeed: not 401 (filter), not 403 (interceptor), not 500")
                .isEqualTo(200);
        assertThat(result.getResponse()
                .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("the origin is echoed BY NAME; a wildcard is incompatible with credentials")
                .isEqualTo("http://localhost:5175");
        assertThat(result.getResponse()
                .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .as("without this the refresh cookie is never stored or sent cross-origin")
                .isEqualTo("true");
        assertThat(result.getResponse()
                .getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .as("Authorization must be allowed or the preflight passes and the real "
                        + "request fails")
                .contains("Authorization");
    }

    // ------------------------------------------------------------------- forced rotation

    /**
     * {@code MUST_CHANGE_PASSWORD='Y'} logs in successfully — refusing the login outright
     * strands the account with no way back — but the token it receives reaches exactly one
     * route.
     */
    @Test
    @DisplayName("a forced-rotation token is refused on every /api route, allowed on "
            + "/auth/change-password")
    void aTokenIssuedUnderForcedRotationIsRefusedOnEveryApiRoute() throws Exception {
        MvcResult loginResult = performLogin(ROTATE, ROTATE_PASSWORD);
        assertThat(loginResult.getResponse().getStatus())
                .as("forced rotation must not block the login itself")
                .isEqualTo(200);
        Map<String, Object> payload = json(loginResult);
        assertThat(payload.get("mustChangePassword")).isEqualTo(true);
        String token = (String) payload.get("token");

        for (String route : API_ROUTES) {
            mockMvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("forced-rotation token on " + route)
                            .isEqualTo(403));
        }
        // The gate must also cover /auth/**, which the interceptor's /api/** mapping never
        // sees — hence AuthFilter carries it too.
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("forced-rotation token on /auth/me")
                        .isEqualTo(403));

        try {
            mockMvc.perform(post(AuthFilter.CHANGE_PASSWORD_PATH)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"" + ROTATE_PASSWORD
                                    + "\",\"newPassword\":\"RotatedPassword#2026\"}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("the ONE route a forced-rotation token may reach; without it "
                                    + "the account can never clear the flag")
                            .isEqualTo(204));
        } finally {
            // Restore the seeded state so the assertion holds on a reused container.
            jdbcTemplate.update("UPDATE APP_USER SET PASSWORD_HASH = ?, "
                    + "MUST_CHANGE_PASSWORD = 'Y' WHERE USERNAME = ?",
                    ROTATE_SEEDED_HASH, ROTATE);
        }
    }

    // --------------------------------------------------------------- the menu-array rule

    /**
     * The invariant {@code menu()} is an ARRAY for. {@code WALLBOARD} holds
     * {@code incident.wallboard:view} and nothing else; scoping these routes to
     * {@code incident.ops} would 403 the unattended TV on the freshness heartbeat that renders
     * its stale banner — and it would go on displaying stale numbers.
     */
    @Test
    @DisplayName("a wallboard-only user reaches /api/freshness and /api/metrics/kpi with 200")
    void aWallboardOnlyUserCanReachFreshnessAndKpi() throws Exception {
        String token = login(WALLBOARD, WALLBOARD_PASSWORD);

        expectStatus("/api/freshness", token, 200);
        expectStatus("/api/metrics/kpi", token, 200);

        // The other half of the rule: widening the grants instead would ALSO unlock the
        // row-level incident list, customer names included.
        expectStatus("/api/incidents", token, 403);
    }

    @Test
    @DisplayName("an exec user reaches kpi, trend and top-categories with 200")
    void anExecUserCanReachKpiTrendAndTopCategories() throws Exception {
        String token = login(EXEC, EXEC_PASSWORD);

        expectStatus("/api/metrics/kpi", token, 200);
        expectStatus("/api/metrics/trend", token, 200);
        expectStatus("/api/metrics/top-categories", token, 200);

        // incident.exec:view is NOT incident.ops:view.
        expectStatus("/api/incidents", token, 403);
        expectStatus("/api/metrics/matrix", token, 403);
    }

    /**
     * {@code export} is deliberately a privilege of its own: bulk egress of production
     * customer and staff names is not the same act as reading a page of them. The VIEWER role
     * differs from OPS_MANAGER by the absence of exactly one grant row.
     */
    @Test
    @DisplayName("a viewer may list incidents but not export them")
    void aViewerCannotExport() throws Exception {
        String viewerToken = login(VIEWER, VIEWER_PASSWORD);
        expectStatus("/api/incidents", viewerToken, 200);
        expectStatus("/api/incidents/export", viewerToken, 403);

        // And the same route IS reachable by the role that holds incident.ops:export, so the
        // 403 above is the missing privilege and not a broken route.
        String opsToken = login(OPS, OPS_PASSWORD);
        expectStatus("/api/incidents/export", opsToken, 200);
    }

    /**
     * AC3.7: a facet is a filter on the SAME route, not a new route. The status/sla facets
     * change what the incident list returns, never who may call it -- and an invalid facet
     * value must not turn a 403 into a 400 that tells an unprivileged caller the route exists.
     */
    @Test
    @DisplayName("a faceted incident query still requires incident.ops view")
    void facetQueryStillRequiresOpsPrivilege() throws Exception {
        String execToken = login(EXEC, EXEC_PASSWORD);
        String wallboardToken = login(WALLBOARD, WALLBOARD_PASSWORD);
        for (String token : List.of(execToken, wallboardToken)) {
            expectStatus("/api/incidents?sla=BREACHED", token, 403);
            expectStatus("/api/incidents?status=OPEN&sla=AT_RISK", token, 403);
            expectStatus("/api/incidents/export?sla=BREACHED", token, 403);
            expectStatus("/api/incidents?sla=NOPE", token, 403);
        }
        // ...while the tiles those users DO see accept the facets.
        expectStatus("/api/metrics/kpi?sla=BREACHED", execToken, 200);
        expectStatus("/api/metrics/kpi?status=CLOSED", wallboardToken, 200);

        // And the route is reachable with the facet by the role that holds the grant.
        String opsToken = login(OPS, OPS_PASSWORD);
        expectStatus("/api/incidents?sla=BREACHED", opsToken, 200);
        expectStatus("/api/incidents?status=OPEN&sla=AT_RISK", opsToken, 200);
    }

    // ----------------------------------------------------------------------- token types

    /**
     * A type-blind {@code validate} would accept the 24-hour refresh token wherever an access
     * token is expected — handing the longest-lived credential the most privilege and making
     * the short access expiry decorative.
     */
    @Test
    @DisplayName("a refresh token presented as a bearer token on an access route is rejected")
    void aRefreshTokenIsRejectedOnAnAccessRoute() throws Exception {
        MvcResult loginResult = performLogin(OPS, OPS_PASSWORD);
        Cookie refreshCookie = loginResult.getResponse().getCookie("refresh");
        assertThat(refreshCookie)
                .as("the refresh token leaves only as a cookie")
                .isNotNull();
        assertThat(json(loginResult))
                .as("and NEVER in the response body, or the cheapest client stashes it in "
                        + "localStorage")
                .doesNotContainKey("refreshToken");

        expectStatus("/api/freshness", refreshCookie.getValue(), 401);
    }

    // ------------------------------------------------------------------------- disclosure

    /**
     * yob's {@code OncePerRequestFilter} — the shape this filter is modelled on — logs the
     * request body. Ported as-is onto {@code POST /auth/token} that writes a plaintext
     * password into the application log, replicated to every sink and retained long after the
     * credential would otherwise have rotated.
     */
    @Test
    @DisplayName("a successful login writes no password to the log, at any level")
    void loginLogsContainNoPassword() throws Exception {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level originalLevel = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        root.addAppender(appender);
        // TRACE, so a "helpful" debug-level body dump cannot hide from this assertion.
        root.setLevel(Level.TRACE);

        MvcResult result;
        try {
            result = performLogin(OPS, OPS_PASSWORD);
        } finally {
            root.setLevel(originalLevel);
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(result.getResponse().getStatus())
                .as("a failed login would make this assertion vacuous")
                .isEqualTo(200);

        StringBuilder captured = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            captured.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                captured.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        assertThat(captured.toString())
                .as("the log must never carry the plaintext password")
                .doesNotContain(OPS_PASSWORD);
        assertThat(appender.list)
                .as("the login WAS logged — otherwise this test proves only that nothing "
                        + "was written at all")
                .anyMatch(event -> event.getFormattedMessage().contains(OPS));
    }

    /**
     * Unknown username, wrong password, locked account and deactivated account must be
     * indistinguishable to the caller. A distinct "account disabled" reply confirms the
     * username exists and turns {@code /auth/token} into a user-enumeration oracle.
     */
    @Test
    @DisplayName("an inactive account fails exactly like a wrong password")
    void anInactiveAccountReturnsTheSameFailureAsAWrongPassword() throws Exception {
        MvcResult inactive = performLogin(INACTIVE, INACTIVE_PASSWORD);
        MvcResult wrongPassword = performLogin(INACTIVE, "definitely-not-the-password");

        assertThat(inactive.getResponse().getStatus())
                .as("a correct password against a deactivated account")
                .isEqualTo(wrongPassword.getResponse().getStatus())
                .isEqualTo(401);

        Map<String, Object> inactiveBody = comparableErrorBody(inactive);
        Map<String, Object> wrongBody = comparableErrorBody(wrongPassword);
        assertThat(inactiveBody)
                .as("identical bodies, or the account state leaks")
                .isEqualTo(wrongBody);
        assertThat(inactiveBody.get("message")).isEqualTo("Invalid username or password.");

        // An unknown username must be indistinguishable too.
        assertThat(comparableErrorBody(performLogin("no.such.user", "whatever-password")))
                .isEqualTo(inactiveBody);
    }

    /**
     * A fail-closed authorization miss surfacing as a 500 reads to operators as an outage and
     * hides a real permission bug behind an alert about something else.
     */
    @Test
    @DisplayName("an authorization refusal is 403 with a body, never 500")
    void accessDeniedIsFourOhThreeNotFiveHundred() throws Exception {
        String token = login(VIEWER, VIEWER_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/incidents/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        Map<String, Object> body = json(result);
        assertThat(body.get("status")).isEqualTo(403);
        assertThat(body.get("error")).isEqualTo("Forbidden");
        assertThat((String) body.get("correlationId")).isNotBlank();
    }

    /**
     * The data behind this service is production-derived. A leaked {@code ORA-} message
     * quoting a table name, or a constraint violation quoting a customer name, is a disclosure
     * to whoever triggered the error — not a debugging aid. The correlation id is the
     * debugging aid.
     */
    @Test
    @DisplayName("a 500 body carries a correlation id and no stack frame or SQL")
    void aServerErrorBodyCarriesNoStackTraceOrSql() throws Exception {
        String token = login(OPS, OPS_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/it-only/boom")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String raw = result.getResponse().getContentAsString();
        Map<String, Object> body = json(result);
        assertThat((String) body.get("correlationId"))
                .as("the correlation id is what maps a user report to one server log line")
                .isNotBlank();
        assertThat(raw)
                .doesNotContain("ORA-00942")
                .doesNotContain("SELECT")
                .doesNotContain("PASSWORD_HASH")
                .doesNotContain("APP_USER")
                .doesNotContain("BadSqlGrammarException")
                .doesNotContain("at id.co.yokke")
                .doesNotContain("java.sql.SQLException");
    }

    // ------------------------------------------------------------------------- authentication

    @Test
    @DisplayName("an /api route without a bearer token is 401, not 403 and not 200")
    void anApiRouteWithoutATokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/freshness"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isEqualTo(401));
        expectStatus("/api/freshness", "not-a-jwt", 401);
    }

    /**
     * The refresh cookie must be HttpOnly, SameSite=Lax, scoped to /auth — and Secure BY
     * DEFAULT, since nothing in this context sets {@code incident.jwt.cookie-secure}.
     *
     * <p>The default is the point. {@code Secure} is what keeps a 24h refresh credential off
     * the wire, and it must never become opt-in by accident. The attribute is configurable
     * only because a browser silently discards a {@code Secure} cookie from a plain-HTTP
     * origin — every origin except {@code localhost}, which is why an HTTP deployment behind
     * an IP loses its session on every reload while localhost testing stays green.
     *
     * <p>Asserted on the raw header rather than through {@code MockHttpServletResponse#getCookie},
     * which does not model SameSite at all.
     */
    @Test
    @DisplayName("the refresh cookie is HttpOnly, Lax, /auth-scoped, and Secure by default")
    void refreshCookieCarriesItsProtectiveAttributes() throws Exception {
        MvcResult result = performLogin("admin.test", "AdminTest#2026");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("login must set the refresh cookie").isNotNull();
        assertThat(setCookie)
                .as("the refresh token must be unreadable to script")
                .contains("HttpOnly")
                // Not Strict: Strict drops the cookie on inbound navigation, so following a
                // Redmine deep link back into the dashboard would silently log the user out.
                .contains("SameSite=Lax")
                // Off every /api call, where it is never needed.
                .contains("Path=/auth")
                .contains("Secure");
    }

    // ------------------------------------------------------------------------------ helpers

    private String login(String username, String password) throws Exception {
        MvcResult result = performLogin(username, password);
        assertThat(result.getResponse().getStatus())
                .as("login for '" + username + "'")
                .isEqualTo(200);
        return (String) json(result).get("token");
    }

    private MvcResult performLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
    }

    private void expectStatus(String route, String token, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(route)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(route + " expected " + expected + ", body was "
                        + result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    /** The error body minus the two fields that are unique per response by design. */
    private Map<String, Object> comparableErrorBody(MvcResult result) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(json(result));
        assertThat((String) body.remove("correlationId"))
                .as("every error body carries a correlation id")
                .isNotBlank();
        body.remove("timestamp");
        return body;
    }

    // --------------------------------------------------------------------- routes under test

    /**
     * The Task 16 / Task 19 routes, annotated exactly as the plan's route map specifies. See
     * the class javadoc: these are TEST-classpath handlers so the authorization chain can be
     * exercised end to end before the real controllers exist, and the owning task deletes the
     * stub it replaces.
     */
    @TestConfiguration
    static class RouteMapUnderTest {

        @Bean
        FailClosedProbes failClosedProbes() {
            return new FailClosedProbes();
        }
    }

    /**
     * Two deliberate defects, under {@code /api/it-only/} so they can never collide with a
     * real route: an endpoint someone forgot to annotate, and one that fails with a database
     * error carrying a table name and a column name.
     */
    @RestController
    static class FailClosedProbes {

        @GetMapping("/api/it-only/undecorated")
        String undecorated() {
            return "this must never be reachable";
        }

        @GetMapping("/api/it-only/boom")
        @RequirePrivilege(menu = "incident.ops", privilege = "view")
        String boom() {
            throw new BadSqlGrammarException("probe",
                    "SELECT PASSWORD_HASH FROM APP_USER WHERE USERNAME = ?",
                    new SQLException("ORA-00942: table or view does not exist"));
        }
    }
}
