package id.co.yokke.incidentdashboard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Parameters menu through the real HTTP chain: who may read, who may write, what is rejected,
 * and that every accepted edit is audited. CFG tables survive {@code MirrorFixtureLoader.resetAll},
 * so each test restores the defaults it touched.
 */
@AutoConfigureMockMvc
class ParametersIT extends AbstractOracleIT {

    private static final String ADMIN = "admin.test";
    private static final String ADMIN_PASSWORD = "AdminTest#2026";
    private static final String OPS = "ops.manager";
    private static final String OPS_PASSWORD = "OpsManager#2026";
    private static final String VIEWER = "viewer";
    private static final String VIEWER_PASSWORD = "Viewer#2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restoreDefaults() {
        jdbc.update("UPDATE CFG_PARAM SET PARAM_VALUE = DEFAULT_VALUE");
        jdbc.update("UPDATE CFG_ALERT_RULE SET IS_ENABLED = DEFAULT_ENABLED, "
                + "SEVERITY = DEFAULT_SEVERITY, MIN_COUNT = DEFAULT_MIN_COUNT, "
                + "THRESHOLD = DEFAULT_THRESHOLD");
        jdbc.update("DELETE FROM CFG_CHANGE_LOG");
    }

    private String tokenFor(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
        }).get("token").toString();
    }

    private MvcResult send(String path, String token, String json) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private JsonNode snapshot(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/config")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private long priorityId(JsonNode snapshot, String name) {
        for (JsonNode item : snapshot.get("slaPolicy")) {
            if (name.equalsIgnoreCase(item.get("priorityName").asText())) {
                return item.get("priorityId").asLong();
            }
        }
        throw new AssertionError("no priority " + name);
    }

    @Test
    @DisplayName("the menu exists with its route; ADMIN manages, OPS_MANAGER only views")
    void menuAndGrants() {
        assertThat(jdbc.queryForObject(
                "SELECT ROUTE FROM APP_MENU WHERE MENU_ID = 'incident.params'", String.class))
                .isEqualTo("/parameters");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM APP_ROLE_PRIVILEGE
                 WHERE ROLE_ID = 'ADMIN' AND MENU_ID = 'incident.params' AND PRIVILEGE = 'manage'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM APP_ROLE_PRIVILEGE
                 WHERE ROLE_ID = 'OPS_MANAGER' AND MENU_ID = 'incident.params'
                   AND PRIVILEGE = 'manage'
                """, Integer.class)).isZero();
    }

    @Test
    @DisplayName("anonymous is 401; a role without the view grant is 403")
    void readIsGated() throws Exception {
        assertThat(mockMvc.perform(get("/api/config")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/config")
                        .header("Authorization", "Bearer " + tokenFor(VIEWER, VIEWER_PASSWORD)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("OPS_MANAGER may read but every write is 403 and changes nothing")
    void opsManagerCannotWrite() throws Exception {
        String token = tokenFor(OPS, OPS_PASSWORD);
        assertThat(snapshot(token).get("params").size()).isGreaterThanOrEqualTo(6);

        assertThat(send("/api/config/params/forecast.default_horizon_days", token,
                "{\"value\":\"14\"}").getResponse().getStatus()).isEqualTo(403);
        assertThat(send("/api/config/alert-rules/AT_RISK", token,
                "{\"enabled\":false}").getResponse().getStatus()).isEqualTo(403);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[]}")
                .getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.queryForObject("SELECT PARAM_VALUE FROM CFG_PARAM "
                + "WHERE PARAM_KEY = 'forecast.default_horizon_days'", String.class))
                .isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CFG_CHANGE_LOG", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("ADMIN edits a scalar param: persisted, canonicalised, audited with the actor")
    void adminEditsParam() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        MvcResult r = send("/api/config/params/forecast.default_horizon_days", token,
                "{\"value\":\" 14 \"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString()).get("value")
                .asText()).isEqualTo("14");

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT CHANGED_BY, TARGET, OLD_VALUE, NEW_VALUE FROM CFG_CHANGE_LOG");
        assertThat(log).containsEntry("CHANGED_BY", ADMIN)
                .containsEntry("TARGET", "param:forecast.default_horizon_days")
                .containsEntry("OLD_VALUE", "7").containsEntry("NEW_VALUE", "14");
        assertThat(snapshot(token).get("recentChanges").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a no-op edit writes no audit row")
    void noopIsNotAudited() throws Exception {
        send("/api/config/params/forecast.default_horizon_days", tokenFor(ADMIN, ADMIN_PASSWORD),
                "{\"value\":\"7\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CFG_CHANGE_LOG", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("invalid params are 400 with the field named and nothing stored")
    void paramValidation() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        String[][] bad = {
                {"forecast.default_horizon_days", "0"},
                {"forecast.default_horizon_days", "31"},
                {"forecast.default_horizon_days", "abc"},
                {"forecast.default_horizon_days", "7.5"},
                {"forecast.smoothing_alpha", "1.5"},
                {"forecast.smoothing_alpha", "0"},
                {"brand.category_prefix", "x5"},
                {"brand.category_prefix", ""},
                {"brand.category_prefix", "0501"},
                {"forecast.smoothing_alpha", "0.30000000000000000000000000000000000001"},
                {"nope.unknown", "1"},
        };
        for (String[] b : bad) {
            MvcResult r = send("/api/config/params/" + b[0], token,
                    "{\"value\":\"" + b[1] + "\"}");
            assertThat(r.getResponse().getStatus()).as(b[0] + "=" + b[1]).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CFG_CHANGE_LOG", Integer.class))
                .isZero();
        assertThat(send("/api/config/params/forecast.smoothing_alpha", token,
                "{\"value\":\"0.5\"}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("alert rule edit round-trips; bad severity / count / threshold are 400")
    void alertRuleEdit() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        MvcResult ok = send("/api/config/alert-rules/AGING_OPEN", token,
                "{\"enabled\":true,\"severity\":\"HIGH\",\"minCount\":2,\"threshold\":45}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode rule = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(rule.get("enabled").asBoolean()).isTrue();
        assertThat(rule.get("severity").asText()).isEqualTo("HIGH");
        assertThat(rule.get("minCount").asInt()).isEqualTo(2);
        assertThat(rule.get("threshold").asDouble()).isEqualTo(45.0);
        assertThat(rule.get("updatedBy").asText()).isEqualTo(ADMIN);

        assertThat(send("/api/config/alert-rules/AGING_OPEN", token, "{\"severity\":\"URGENT\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/config/alert-rules/AGING_OPEN", token, "{\"minCount\":0}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/config/alert-rules/AGING_OPEN", token, "{\"threshold\":-1}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/config/alert-rules/AT_RISK", token, "{\"threshold\":5}")
                .getResponse().getStatus()).as("AT_RISK has no threshold").isEqualTo(400);
        assertThat(send("/api/config/alert-rules/AGING_OPEN", token, "null")
                .getResponse().getStatus()).as("null body").isEqualTo(400);
        assertThat(send("/api/config/alert-rules/NOPE", token, "{\"enabled\":false}")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("disabling a rule removes it from /api/insights/alerts")
    void disabledRuleDoesNotFire() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        send("/api/config/alert-rules/UNASSIGNED_OPEN", token, "{\"enabled\":false}");
        String alerts = mockMvc.perform(get("/api/insights/alerts")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(alerts).doesNotContain("UNASSIGNED_OPEN");
    }

    @Test
    @DisplayName("SLA edit: persisted, audited, and bumps the config generation")
    void slaEdit() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        JsonNode before = snapshot(token);
        long low = priorityId(before, "Low");
        int oldDays = 0;
        int cushion = 0;
        for (JsonNode i : before.get("slaPolicy")) {
            if (i.get("priorityId").asLong() == low) {
                oldDays = i.get("slaDays").asInt();
                cushion = i.get("atRiskRemainingDays").asInt();
            }
        }
        Long generation = jdbc.queryForObject("SELECT GENERATION FROM CFG_VERSION", Long.class);
        try {
            MvcResult r = send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":"
                    + low + ",\"slaDays\":" + (oldDays + 5) + ",\"atRiskRemainingDays\":"
                    + cushion + "}]}");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT SLA_DAYS FROM CFG_SLA_POLICY "
                    + "WHERE PRIORITY_ID = ?", Integer.class, low)).isEqualTo(oldDays + 5);
            assertThat(jdbc.queryForObject("SELECT GENERATION FROM CFG_VERSION", Long.class))
                    .isGreaterThan(generation);
            assertThat(jdbc.queryForObject("SELECT TARGET FROM CFG_CHANGE_LOG", String.class))
                    .isEqualTo("sla:Low");
        } finally {
            jdbc.update("UPDATE CFG_SLA_POLICY SET SLA_DAYS = ? WHERE PRIORITY_ID = ?",
                    oldDays, low);
        }
    }

    @Test
    @DisplayName("SLA validation: ladder order, cushion < days, unknown/duplicate priority are 400")
    void slaValidation() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        JsonNode snap = snapshot(token);
        long critical = priorityId(snap, "Critical");
        long low = priorityId(snap, "Low");

        // Critical longer than Low breaks the ladder.
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":" + critical
                + ",\"slaDays\":500,\"atRiskRemainingDays\":1}]}")
                .getResponse().getStatus()).as("500 days > 365 cap").isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":" + critical
                + ",\"slaDays\":300,\"atRiskRemainingDays\":1}]}")
                .getResponse().getStatus()).as("critical 300d > low 60d").isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":" + low
                + ",\"slaDays\":60,\"atRiskRemainingDays\":60}]}")
                .getResponse().getStatus()).as("cushion must be < days").isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":" + low
                + ",\"slaDays\":0,\"atRiskRemainingDays\":0}]}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token,
                "{\"items\":[{\"priorityId\":999999,\"slaDays\":5,\"atRiskRemainingDays\":1}]}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[{\"priorityId\":" + low
                + ",\"slaDays\":60,\"atRiskRemainingDays\":2},{\"priorityId\":" + low
                + ",\"slaDays\":60,\"atRiskRemainingDays\":2}]}")
                .getResponse().getStatus()).as("duplicate").isEqualTo(400);
        assertThat(send("/api/config/sla-policy", token, "{\"items\":[]}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CFG_CHANGE_LOG", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("forecast horizon is optional: omitted uses the configured default")
    void forecastDefaultHorizon() throws Exception {
        String token = tokenFor(ADMIN, ADMIN_PASSWORD);
        send("/api/config/params/forecast.default_horizon_days", token, "{\"value\":\"14\"}");
        JsonNode forecast = objectMapper.readTree(mockMvc.perform(
                        get("/api/insights/forecast").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertThat(forecast.get("horizonDays").asInt()).isEqualTo(14);
        assertThat(forecast.get("days").size()).isEqualTo(14);
    }
}
