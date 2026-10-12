package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * TM06: the wallboard payload. Relations, not transcribed arithmetic: stages and priorities sum
 * to the total, open + closed == total, compliance is met / closed, the year is zero-filled
 * January to now, and nothing but Incident-scope tickets of the current year is counted.
 */
@AutoConfigureMockMvc
class WallboardIT extends AbstractOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("TM06 the wallboard payload carries every field and its invariants hold")
    void payloadFieldsAndInvariants() throws Exception {
        seed();
        Map<String, Object> body = wallboard(login("wallboard.user", "Wallboard#2026"));

        assertThat(body.keySet()).containsExactlyInAnyOrder("generatedAt", "year", "total",
                "open", "closed", "breached", "atRisk", "slaCompliance", "byStage",
                "byPriority", "criticalHighOpen", "topCategories", "monthly",
                "avgResolutionDays", "groupCounts");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        assertThat(number(body, "year")).isEqualTo(today.getYear());
        assertThat(body.get("generatedAt")).isInstanceOf(String.class);

        long total = number(body, "total");
        assertThat(total).as("2 open Incidents + 3 closed Incidents; Problem, cancelled and "
                + "last-year rows are not in the population").isEqualTo(5);
        assertThat(number(body, "open") + number(body, "closed")).isEqualTo(total);
        assertThat(number(body, "open")).isEqualTo(2);

        assertThat(sum(list(body, "byStage"))).as("stages partition the total").isEqualTo(total);
        assertThat(sum(list(body, "byPriority"))).as("priorities partition the total")
                .isEqualTo(total);
        assertThat(list(body, "byPriority")).extracting(row -> row.get("priority"))
                .as("Critical, High, Medium, Low order, absent ones omitted")
                .containsExactly("Critical", "High", "Low");

        Map<String, Object> compliance = map(body, "slaCompliance");
        assertThat(number(compliance, "closedInPeriod")).isEqualTo(number(body, "closed"));
        assertThat(number(compliance, "met")).isEqualTo(2);   // one of the three closed was late
        assertThat(number(compliance, "percent"))
                .isEqualTo(Math.round(100.0 * number(compliance, "met")
                        / number(compliance, "closedInPeriod")));

        assertThat(list(body, "criticalHighOpen")).hasSize(2)
                .allSatisfy(row -> assertThat(row.get("priority")).isIn("Critical", "High"));
        assertThat(list(body, "criticalHighOpen").get(0).keySet()).containsExactlyInAnyOrder(
                "noIncident", "subject", "status", "priority", "elapsedDays", "slaDays");

        List<Map<String, Object>> monthly = list(body, "monthly");
        assertThat(monthly).as("January to the current month, zero-filled")
                .hasSize(today.getMonthValue());
        assertThat(monthly.get(0).get("month")).isEqualTo(today.getYear() + "-01");
        assertThat(monthly.stream().mapToLong(month -> number(month, "created")).sum())
                .as("every counted ticket was created in some month of the year")
                .isEqualTo(total);

        assertThat(((Number) body.get("avgResolutionDays")).doubleValue()).isNotNegative();

        List<Map<String, Object>> groups = list(body, "groupCounts");
        assertThat(groups).extracting(group -> group.get("scope"))
                .containsExactly("INCIDENT", "PROBLEM", "SERVICE_REQUEST");
        assertThat(number(groups.get(0), "total")).isEqualTo(total);
        assertThat(number(groups.get(1), "total")).as("Problem is counted in the tile").isEqualTo(1);
        assertThat(list(body, "topCategories")).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("TM06 an empty year yields null compliance and average, and a zero-filled year")
    void emptyYear() throws Exception {
        Map<String, Object> body = wallboard(login("wallboard.user", "Wallboard#2026"));

        assertThat(number(body, "total")).isZero();
        assertThat(map(body, "slaCompliance").get("percent")).isNull();
        assertThat(body.get("avgResolutionDays")).isNull();
        assertThat(list(body, "criticalHighOpen")).isEmpty();
        assertThat(list(body, "monthly")).hasSize(
                LocalDate.now(ZoneId.of("Asia/Jakarta")).getMonthValue())
                .allSatisfy(month -> assertThat(number(month, "created")).isZero());
        assertThat(list(body, "groupCounts")).hasSize(3);
    }

    @Test
    @DisplayName("TM06 ticket subjects reach only callers who may read the incident list")
    void subjectsAreWithheldFromWallboardAndExecCallers() throws Exception {
        seed();
        for (String[] account : new String[][] {{"wallboard.user", "Wallboard#2026"},
                {"exec.user", "ExecUser#2026"}}) {
            List<Map<String, Object>> rows = list(wallboard(login(account[0], account[1])),
                    "criticalHighOpen");
            assertThat(rows).as(account[0]).hasSize(2).allSatisfy(row -> {
                assertThat(row.get("subject")).isNull();
                assertThat(row.get("noIncident")).isNotNull();
                assertThat(row.get("status")).isNotNull();
                assertThat(row.get("priority")).isNotNull();
                assertThat(row.get("slaDays")).isNotNull();
            });
        }
        assertThat(list(wallboard(login("ops.manager", "OpsManager#2026")), "criticalHighOpen"))
                .hasSize(2).allSatisfy(row -> assertThat(row.get("subject")).isNotNull());
        assertThat(list(wallboard(login("viewer", "Viewer#2026")), "criticalHighOpen"))
                .allSatisfy(row -> assertThat(row.get("subject")).isNotNull());
    }

    @Test
    @DisplayName("TM06 no query parameter can change the scope or the window")
    void noParameterIsHonored() throws Exception {
        seed();
        String token = login("ops.manager", "OpsManager#2026");
        Map<String, Object> plain = wallboard(token);
        Map<String, Object> tampered = objectMapper.readValue(mockMvc.perform(
                        get("/api/metrics/wallboard").param("scope", "PROBLEM")
                                .param("dateFrom", "2001-01-01").param("tracker", "Problem")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
        plain.remove("generatedAt");
        tampered.remove("generatedAt");
        assertThat(tampered).isEqualTo(plain);
    }

    // ---------------------------------------------------------------------------- fixtures

    private void seed() {
        insertStatus(20, "Open", 0);
        insertStatus(5, "Closed", 1);
        insertStatus(6, "Drop", 1);
        insertTracker(7, "Incident");
        insertTracker(41, "Problem");
        insertPriority(4, "Low");
        insertPriority(5, "High");
        insertPriority(6, "Critical");
        // Incident, this year. `GREATEST(..)` keeps a 1 January run inside the year.
        fact(1, 7, 20, 6, 3, "GREATEST(TRUNC(SYSDATE, 'YYYY'), TRUNC(SYSDATE) - 2)", Map.of());
        fact(2, 7, 20, 5, 7, "GREATEST(TRUNC(SYSDATE, 'YYYY'), TRUNC(SYSDATE) - 1)", Map.of());
        fact(3, 7, 5, 4, 60, "TRUNC(SYSDATE, 'YYYY')", Map.of(     // closed in time
                "CLOSE_DATE", "TRUNC(SYSDATE, 'YYYY')", "HAS_CLOSE_DATE_ROW", "1"));
        fact(4, 7, 5, 4, 60, "TRUNC(SYSDATE, 'YYYY')", Map.of(     // closed in time
                "CLOSED_ON", "TRUNC(SYSDATE, 'YYYY')",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE, 'YYYY')"));
        fact(5, 7, 5, 4, 0, "TRUNC(SYSDATE, 'YYYY')", Map.of(      // closed late (SLA 0)
                "CLOSED_ON", "TRUNC(SYSDATE, 'YYYY') + 1",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE, 'YYYY') + 1"));
        // Outside the population: another group, cancelled, and last year.
        fact(6, 41, 20, 4, 60, "TRUNC(SYSDATE) - 1", Map.of());
        fact(7, 7, 6, 4, 60, "TRUNC(SYSDATE) - 1", Map.of());
        fact(8, 7, 20, 4, 60, "ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -1)", Map.of());
    }

    private void fact(long id, int tracker, int status, int priority, int sla,
            String createExpr, Map<String, String> extra) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("NO_INCIDENT", Long.toString(id));
        columns.put("TRACKER_ID", Integer.toString(tracker));
        columns.put("SUBJECT", "'incident " + id + "'");
        columns.put("DATE_CREATE", createExpr);
        columns.put("CREATE_MONTH", "TO_CHAR(" + createExpr + ", 'YYYYMM')");
        columns.put("STATUS_ID", Integer.toString(status));
        columns.put("PRIORITY_ID", Integer.toString(priority));
        columns.put("SLA_DAYS", Integer.toString(sla));
        columns.put("HAS_CLOSE_DATE_ROW", "0");
        columns.put("HAS_RESOLUTION_ROW", "0");
        columns.put("LAST_DATE_STATUS", createExpr);
        columns.put("ETL_UPDATED_AT", "SYSDATE");
        columns.put("SOURCE_UPDATED_ON", "SYSDATE");
        columns.putAll(extra);
        jdbc.update("INSERT INTO INCIDENT_FACT (" + String.join(", ", columns.keySet())
                + ") VALUES (" + String.join(", ", columns.values()) + ")");
    }

    private void insertStatus(long id, String name, int isClosed) {
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("issue_statuses")
                + " (ID, NAME, IS_CLOSED, POSITION) VALUES (" + id + ", '" + name + "', "
                + isClosed + ", 1)");
    }

    private void insertTracker(long id, String name) {
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("trackers")
                + " (ID, NAME, POSITION) VALUES (" + id + ", '" + name + "', 1)");
    }

    private void insertPriority(long id, String name) {
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("enumerations")
                + " (ID, NAME, TYPE, POSITION, ACTIVE) VALUES (" + id + ", '" + name
                + "', 'IssuePriority', 1, 1)");
    }

    // ---------------------------------------------------------------------------- helpers

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + username).isEqualTo(200);
        return (String) objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                }).get("token");
    }

    private Map<String, Object> wallboard(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/metrics/wallboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String field) {
        return (Map<String, Object>) body.get(field);
    }

    private static long sum(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(row -> number(row, "count")).sum();
    }

    private static long number(Map<String, Object> body, String field) {
        Object value = body.get(field);
        assertThat(value).as(field + " is present and numeric").isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}
