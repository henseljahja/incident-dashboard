package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.usecase.etl.DerivedFields;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/metrics/definitions} — the numbers behind every ⓘ.
 *
 * <p>Two properties matter. The numbers are READ from config at request time, never typed into
 * the response (a cushion changed in {@code CFG_SLA_POLICY} must change the ⓘ text without a
 * deploy), and the route is as closed as the tiles it explains.
 */
@AutoConfigureMockMvc
class DefinitionsIT extends AbstractOracleIT {

    private static final String ROUTE = "/api/metrics/definitions";

    /** A throwaway role/user holding a real grant that is NOT a dashboard grant. */
    private static final String NO_DASHBOARD_ROLE = "IT_NO_DASHBOARD";
    private static final String NO_DASHBOARD_USER = "it.no.dashboard";
    /** The account is created with viewer's hash, so it logs in with viewer's password. */
    private static final String NO_DASHBOARD_PASSWORD = "Viewer#2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${incident.etl.refresh-interval-ms}")
    private long refreshIntervalMs;

    @Test
    @DisplayName("definitions return the live SLA policy, override statuses and refresh cadence")
    @SuppressWarnings("unchecked")
    void returnsLivePolicyNumbers() throws Exception {
        String token = login("ops.manager", "OpsManager#2026");
        Map<String, Object> body = getJson(token);

        List<Map<String, Object>> policy = (List<Map<String, Object>>) body.get("slaPolicy");
        assertThat(policy)
                .as("ordered by slaDays ascending: Critical, High, Medium, Low")
                .extracting(item -> ((Number) item.get("slaDays")).intValue())
                .containsExactly(3, 7, 14, 60);
        assertThat(policy)
                .as("AC6.2: the cushion per priority, as V9 seeds it")
                .extracting(item -> item.get("priorityName") + ":"
                        + item.get("atRiskRemainingDays"))
                .containsExactly("Critical:1", "High:2", "Medium:3", "Low:7");
        assertThat(policy)
                .extracting(item -> ((Number) item.get("priorityId")).longValue())
                .containsExactly(21L, 3L, 19L, 1L);

        Map<String, Object> unknown = (Map<String, Object>) body.get("unknownPriority");
        assertThat(((Number) unknown.get("slaDays")).intValue())
                .isEqualTo(DerivedFields.UNKNOWN_PRIORITY_SLA_DAYS);
        assertThat(((Number) unknown.get("atRiskRemainingDays")).intValue())
                .isEqualTo(DerivedFields.UNKNOWN_PRIORITY_AT_RISK_DAYS)
                .isEqualTo(1);

        assertThat((List<Map<String, Object>>) body.get("closedByOverride"))
                .extracting(item -> item.get("statusId") + ":" + item.get("statusName"))
                .containsExactly("11:PTR", "63:PTR with permission");

        assertThat((List<String>) body.get("closeRule"))
                .as("stop-clock precedence, as enum names the frontend translates")
                .containsExactly("CLOSE_DATE", "CLOSED_ON", "STATUS_CHANGE");

        assertThat(((Number) body.get("refreshIntervalMinutes")).doubleValue())
                .as("derived from incident.etl.refresh-interval-ms, not typed")
                .isEqualTo(refreshIntervalMs / 60_000.0);

        // LIVE, not cached or constant: a config edit shows on the next request.
        Integer before = jdbcTemplate.queryForObject(
                "SELECT AT_RISK_REMAINING_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 1",
                Integer.class);
        try {
            jdbcTemplate.update(
                    "UPDATE CFG_SLA_POLICY SET AT_RISK_REMAINING_DAYS = 10 WHERE PRIORITY_ID = 1");
            List<Map<String, Object>> edited =
                    (List<Map<String, Object>>) getJson(token).get("slaPolicy");
            assertThat(edited)
                    .filteredOn(item -> ((Number) item.get("priorityId")).longValue() == 1L)
                    .extracting(item -> ((Number) item.get("atRiskRemainingDays")).intValue())
                    .containsExactly(10);
        } finally {
            jdbcTemplate.update(
                    "UPDATE CFG_SLA_POLICY SET AT_RISK_REMAINING_DAYS = ? WHERE PRIORITY_ID = 1",
                    before);
        }

        // Every dashboard audience that renders an ⓘ can read it.
        expectStatus(login("exec.user", "ExecUser#2026"), 200);
        expectStatus(login("wallboard.user", "Wallboard#2026"), 200);
    }

    /**
     * AC6.5. No seeded account lacks every dashboard grant, so one is created here: its only
     * grant is {@code incident.admin:view}, which proves the 403 is the MISSING dashboard
     * privilege and not a user with no roles at all.
     */
    @Test
    @DisplayName("definitions refuse a user without a dashboard view privilege")
    void requiresDashboardPrivilege() throws Exception {
        try {
            jdbcTemplate.update("INSERT INTO APP_ROLE (ROLE_ID, DISPLAY_NAME, DEFAULT_ROUTE, "
                    + "ROLE_PRIORITY) VALUES (?, 'IT no dashboard', '/admin/users', 9991)",
                    NO_DASHBOARD_ROLE);
            jdbcTemplate.update("INSERT INTO APP_ROLE_PRIVILEGE (ROLE_ID, MENU_ID, PRIVILEGE) "
                    + "VALUES (?, 'incident.admin', 'view')", NO_DASHBOARD_ROLE);
            jdbcTemplate.update("INSERT INTO APP_USER (USERNAME, PASSWORD_HASH, DISPLAY_NAME, "
                    + "IS_ACTIVE, MUST_CHANGE_PASSWORD) SELECT ?, PASSWORD_HASH, "
                    + "'IT no dashboard', 'Y', 'N' FROM APP_USER WHERE USERNAME = 'viewer'",
                    NO_DASHBOARD_USER);
            jdbcTemplate.update("INSERT INTO APP_USER_ROLE (USERNAME, ROLE_ID) VALUES (?, ?)",
                    NO_DASHBOARD_USER, NO_DASHBOARD_ROLE);

            String token = login(NO_DASHBOARD_USER, NO_DASHBOARD_PASSWORD);
            MvcResult refused = perform(token);
            assertThat(refused.getResponse().getStatus())
                    .as("body was " + refused.getResponse().getContentAsString())
                    .isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a refusal carries no policy numbers")
                    .doesNotContain("slaPolicy")
                    .doesNotContain("closedByOverride");

            // The same account is not broken: the tiles the ⓘ explains are refused too.
            MvcResult kpi = mockMvc.perform(get("/api/metrics/kpi")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn();
            assertThat(kpi.getResponse().getStatus()).isEqualTo(403);
        } finally {
            // ON DELETE CASCADE removes the user-role, privilege and refresh-token rows.
            jdbcTemplate.update("DELETE FROM APP_USER WHERE USERNAME = ?", NO_DASHBOARD_USER);
            jdbcTemplate.update("DELETE FROM APP_ROLE WHERE ROLE_ID = ?", NO_DASHBOARD_ROLE);
        }

        // And without a token at all it is unauthenticated, not open.
        assertThat(mockMvc.perform(get(ROUTE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------------------ helpers

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login for " + username).isEqualTo(200);
        return (String) json(result).get("token");
    }

    private MvcResult perform(String token) throws Exception {
        return mockMvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private void expectStatus(String token, int expected) throws Exception {
        MvcResult result = perform(token);
        assertThat(result.getResponse().getStatus())
                .as("body was " + result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private Map<String, Object> getJson(String token) throws Exception {
        MvcResult result = perform(token);
        assertThat(result.getResponse().getStatus())
                .as("body was " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json(result);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }
}
