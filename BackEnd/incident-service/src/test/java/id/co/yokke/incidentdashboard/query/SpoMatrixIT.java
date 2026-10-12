package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Pras, 8 Oct: the single MTTR and the SPO priority matrix (V11). */
@AutoConfigureMockMvc
class SpoMatrixIT extends AbstractOracleIT {

    private static final List<String> IMPACTS = List.of("Massive", "Large", "Limited", "Localized");
    private static final List<String> URGENCIES = List.of("Urgent", "High", "Medium", "Low");

    /** Rows = urgency (Urgent..Low), columns = impact (Massive..Localized): the SPO sheet. */
    private static final String[][] SPO = {
            {"Critical", "Critical", "High", "High"},
            {"Critical", "High", "High", "Medium"},
            {"High", "Medium", "Medium", "Medium"},
            {"Low", "Low", "Low", "Low"}};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V11 seeds exactly the 16 SPO cells, and the four axis labels each way")
    void v11SeedsSixteenCells() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CFG_PRIORITY_MATRIX", Long.class))
                .isEqualTo(16);
        for (int u = 0; u < 4; u++) {
            for (int i = 0; i < 4; i++) {
                assertThat(jdbc.queryForObject("SELECT PRIORITY FROM CFG_PRIORITY_MATRIX "
                                + "WHERE IMPACT = ? AND URGENCY = ?", String.class,
                        IMPACTS.get(i), URGENCIES.get(u)))
                        .as(IMPACTS.get(i) + " x " + URGENCIES.get(u)).isEqualTo(SPO[u][i]);
            }
        }
    }

    @Test
    @DisplayName("the matrix returns the SPO priority per cell, null outside the SPO, and the axis order")
    @SuppressWarnings("unchecked")
    void matrixCarriesSpoPriorityAndOrder() throws Exception {
        seed();
        long id = 1;
        for (String impact : IMPACTS) {
            for (String urgency : URGENCIES) {
                insert(id++, 7, "20", impact, urgency, Map.of());
            }
        }
        // Case/whitespace-insensitive join; a placeholder; an unresolved enum id.
        insert(id++, 7, "20", " massive ", "MEDIUM", Map.of());
        insert(id++, 7, "20", "Unclassified", "Urgent", Map.of());
        insert(id, 7, "20", "Unknown (9)", "Low", Map.of());

        Map<String, Object> matrix = getObject("/api/metrics/matrix", token());
        assertThat((List<String>) matrix.get("impactOrder")).isEqualTo(IMPACTS);
        assertThat((List<String>) matrix.get("urgencyOrder")).isEqualTo(URGENCIES);

        Map<String, String> byPair = new HashMap<>();
        for (Map<String, Object> cell : (List<Map<String, Object>>) matrix.get("cells")) {
            byPair.put(cell.get("impact") + "|" + cell.get("urgency"),
                    (String) cell.get("priority"));
            assertThat(cell.keySet()).contains("impact", "urgency", "count", "priority");
        }
        for (int u = 0; u < 4; u++) {
            for (int i = 0; i < 4; i++) {
                assertThat(byPair).containsEntry(IMPACTS.get(i) + "|" + URGENCIES.get(u),
                        SPO[u][i]);
            }
        }
        assertThat(byPair.get("Massive|Medium")).isEqualTo("High");
        assertThat(byPair.get("Limited|Urgent")).isEqualTo("High");
        assertThat(byPair.get(" massive |MEDIUM")).as("matched case-insensitively and trimmed")
                .isEqualTo("High");
        assertThat(byPair).containsEntry("Unclassified|Urgent", null)
                .containsEntry("Unknown (9)|Low", null);
        assertThat(number(matrix, "missingCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("mttrDays is the mean elapsed days of closed rows, null when none, facets and scope respected")
    void singleMttr() throws Exception {
        seed();
        String token = token();
        assertThat(getObject("/api/metrics/kpi", token).get("mttrDays"))
                .as("nothing closed").isNull();

        // Closed Incident rows with different ages (SLA 60 and 5), one open row, one Problem.
        insert(1, 7, "5", "High", "High", Map.of("SLA_DAYS", "60",
                "DATE_CREATE", "TRUNC(SYSDATE) - 10", "CLOSED_ON", "TRUNC(SYSDATE) - 4",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE) - 4"));
        insert(2, 7, "5", "High", "High", Map.of("SLA_DAYS", "60",
                "DATE_CREATE", "TRUNC(SYSDATE) - 20", "CLOSED_ON", "TRUNC(SYSDATE) - 3",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE) - 3"));
        insert(3, 7, "5", "High", "High", Map.of("SLA_DAYS", "5",
                "DATE_CREATE", "TRUNC(SYSDATE) - 30", "CLOSED_ON", "TRUNC(SYSDATE) - 1",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE) - 1"));
        insert(4, 7, "20", "High", "High", Map.of("DATE_CREATE", "TRUNC(SYSDATE) - 40"));
        insert(5, 41, "5", "High", "High", Map.of("SLA_DAYS", "60",
                "DATE_CREATE", "TRUNC(SYSDATE) - 9", "CLOSED_ON", "TRUNC(SYSDATE) - 8",
                "LAST_DATE_STATUS", "TRUNC(SYSDATE) - 8"));

        assertThat(mttr(token)).as("all closed rows, open row excluded")
                .isEqualTo(expected("IS_CLOSED = 1"));
        assertThat(mttr(token, "scope", "PROBLEM"))
                .isEqualTo(expected("IS_CLOSED = 1 AND TRACKER_ID = 41")).isEqualTo(1.0);
        assertThat(mttr(token, "scope", "INCIDENT"))
                .isEqualTo(expected("IS_CLOSED = 1 AND TRACKER_ID = 7"));
        assertThat(mttr(token, "sla", "BREACHED"))
                .as("the sla facet narrows it exactly as it narrows the Closed tile")
                .isEqualTo(expected("IS_CLOSED = 1 AND SLA_BREACHED = 1"));
        assertThat(mttr(token, "sla", "BREACHED")).isNotEqualTo(mttr(token));

        Map<String, Object> bounded = getObject("/api/metrics/kpi", token,
                "dateFrom", "2000-01-01", "dateTo", "2000-01-31");
        assertThat(bounded.get("mttrDays")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> previous = (Map<String, Object>) bounded.get("previousPeriod");
        assertThat(previous).containsKey("mttrDays");

        // Deprecated fields stay in the payload for one release.
        assertThat(getObject("/api/metrics/kpi", token)).containsKeys("mttrWorkaroundDays",
                "mttrPermanentDays", "mttrGapDays", "workaroundDebt", "mttrDays");
    }

    private Double mttr(String token, String... params) throws Exception {
        Object value = getObject("/api/metrics/kpi", token, params).get("mttrDays");
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Double expected(String where) {
        return jdbc.queryForObject("SELECT ROUND(AVG(ELAPSED_DAYS), 2) FROM V_INCIDENT WHERE "
                + where + " AND EXCLUDE_FROM_BACKLOG = 0", Double.class);
    }

    // ---------------------------------------------------------------------------- fixtures

    private void seed() {
        insertRef("issue_statuses", "ID, NAME, IS_CLOSED, POSITION", "20, 'Open', 0, 1");
        insertRef("issue_statuses", "ID, NAME, IS_CLOSED, POSITION", "5, 'Closed', 1, 1");
        insertRef("trackers", "ID, NAME, POSITION", "7, 'Incident', 1");
        insertRef("trackers", "ID, NAME, POSITION", "41, 'Problem', 1");
        insertRef("enumerations", "ID, NAME, TYPE, POSITION, ACTIVE", "4, 'Low', 'IssuePriority', 1, 1");
    }

    private void insertRef(String table, String columns, String values) {
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable(table) + " (" + columns
                + ") VALUES (" + values + ")");
    }

    private void insert(long id, int tracker, String status, String impact, String urgency,
            Map<String, String> extra) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("NO_INCIDENT", Long.toString(id));
        columns.put("TRACKER_ID", Integer.toString(tracker));
        columns.put("SUBJECT", "'incident " + id + "'");
        columns.put("DATE_CREATE", "SYSDATE - 3");
        columns.put("STATUS_ID", status);
        columns.put("PRIORITY_ID", "4");
        columns.put("SLA_DAYS", "60");
        columns.put("IMPACT_SEVERITY", "'" + impact + "'");
        columns.put("URGENCY_SEVERITY", "'" + urgency + "'");
        columns.put("HAS_CLOSE_DATE_ROW", "0");
        columns.put("HAS_RESOLUTION_ROW", "0");
        columns.put("LAST_DATE_STATUS", "SYSDATE - 3");
        columns.put("ETL_UPDATED_AT", "SYSDATE");
        columns.put("SOURCE_UPDATED_ON", "SYSDATE");
        columns.putAll(extra);
        columns.put("CREATE_MONTH", "TO_CHAR(" + columns.get("DATE_CREATE") + ", 'YYYYMM')");
        jdbc.update("INSERT INTO INCIDENT_FACT (" + String.join(", ", columns.keySet())
                + ") VALUES (" + String.join(", ", columns.values()) + ")");
    }

    // ------------------------------------------------------------------------------ http

    private String token() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "ops.manager", "password", "OpsManager#2026"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (String) objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                }).get("token");
    }

    private Map<String, Object> getObject(String url, String token, String... params)
            throws Exception {
        MockHttpServletRequestBuilder request = get(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(url + " " + result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private static long number(Map<String, Object> body, String field) {
        return ((Number) body.get(field)).longValue();
    }
}
