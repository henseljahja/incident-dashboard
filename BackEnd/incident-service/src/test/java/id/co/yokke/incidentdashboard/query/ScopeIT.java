package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Phase 2: scoped menus (TM01-TM05, TM07, TM08).
 *
 * <p>Assertions are relations and invariants -- scope counts sum to the unscoped total, a scoped
 * KPI tile equals the scoped table total, {@code missingCount} opens exactly that many rows --
 * rather than a transcription of one fixture's arithmetic.
 */
@AutoConfigureMockMvc
class ScopeIT extends AbstractOracleIT {

    private static final String OPEN = "20";
    private static final String CLOSED = "5";
    private static final String CANCELLED = "6";

    private static final List<String> SCOPES = List.of("INCIDENT", "PROBLEM", "SERVICE_REQUEST");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------------- TM01 / TM03

    @Test
    @DisplayName("TM01 per-scope counts sum to the unscoped total and open + closed == total")
    void scopeCountsPartitionTheUnscopedPopulation() throws Exception {
        seedMixedTrackers();
        String token = login("ops.manager", "OpsManager#2026");

        Map<String, Object> body = getObject("/api/metrics/scope-counts", token);
        List<Map<String, Object>> scopes = list(body, "scopes");

        assertThat(scopes).extracting(scope -> scope.get("scope"))
                .as("always three entries, in this order, even for an empty group")
                .containsExactlyElementsOf(SCOPES);
        for (Map<String, Object> scope : scopes) {
            assertThat(number(scope, "open") + number(scope, "closed"))
                    .as(scope.get("scope") + ": open + closed == total")
                    .isEqualTo(number(scope, "total"));
        }

        Map<String, Object> unscoped = getObject("/api/metrics/kpi", token);
        assertThat(scopes.stream().mapToLong(scope -> number(scope, "total")).sum())
                .as("the three groups partition the unscoped population")
                .isEqualTo(number(unscoped, "open") + number(unscoped, "closed"));
        assertThat(scopes.stream().mapToLong(scope -> number(scope, "total")).sum())
                .as("and the unscoped table")
                .isEqualTo(number(getObject("/api/incidents", token), "totalElements"));

        for (Map<String, Object> scope : scopes) {
            assertThat(number(scope, "total"))
                    .as(scope.get("scope") + " is not vacuous in this fixture")
                    .isPositive();
        }
    }

    @Test
    @DisplayName("TM01 scope-counts honors the date window and ignores scope and tracker")
    void scopeCountsHonorDatesOnly() throws Exception {
        seedMixedTrackers();
        String token = login("ops.manager", "OpsManager#2026");

        Map<String, Object> narrowed = getObject("/api/metrics/scope-counts", token,
                "scope", "PROBLEM", "tracker", "Problem");
        Map<String, Object> plain = getObject("/api/metrics/scope-counts", token);
        assertThat(narrowed).isEqualTo(plain);

        Map<String, Object> future = getObject("/api/metrics/scope-counts", token,
                "dateFrom", "2999-01-01", "dateTo", "2999-12-31");
        assertThat(list(future, "scopes")).hasSize(3)
                .allSatisfy(scope -> assertThat(number(scope, "total")).isZero());
    }

    @Test
    @DisplayName("TM03 a scoped KPI equals the scoped table total, for every scope")
    void scopedKpiEqualsScopedTableTotal() throws Exception {
        seedMixedTrackers();
        String token = login("ops.manager", "OpsManager#2026");

        long sum = 0;
        for (String scope : SCOPES) {
            Map<String, Object> kpi = getObject("/api/metrics/kpi", token, "scope", scope);
            long table = number(getObject("/api/incidents", token, "scope", scope),
                    "totalElements");
            assertThat(number(kpi, "open") + number(kpi, "closed"))
                    .as(scope + ": KPI tiles == table total")
                    .isEqualTo(table);
            long matrix = list(getObject("/api/metrics/matrix", token, "scope", scope), "cells")
                    .stream().mapToLong(cell -> number(cell, "count")).sum();
            assertThat(matrix).as(scope + ": matrix cells == table total").isEqualTo(table);
            long buckets = getList("/api/metrics/sla-buckets", token, "scope", scope).stream()
                    .mapToLong(bucket -> number(bucket, "count")).sum();
            assertThat(buckets).as(scope + ": SLA bands == table total").isEqualTo(table);
            sum += table;
        }
        assertThat(sum).isEqualTo(number(getObject("/api/incidents", token), "totalElements"));

        // Case-insensitive, and the same population as the canonical token.
        assertThat(number(getObject("/api/incidents", token, "scope", "problem"),
                "totalElements"))
                .isEqualTo(number(getObject("/api/incidents", token, "scope", "PROBLEM"),
                        "totalElements"));
        // Group membership is by tracker: SR spans three trackers.
        List<Map<String, Object>> sr = content(getObject("/api/incidents", token,
                "scope", "SERVICE_REQUEST"));
        assertThat(sr).extracting(row -> row.get("TRACKER_ID").toString())
                .containsOnly("16", "32", "33");
    }

    // ----------------------------------------------------------------------------- TM02

    @Test
    @DisplayName("TM02 an unknown scope is a 400 invalid_filter on every filter-bearing route")
    void unknownScopeIsRefused() throws Exception {
        String token = login("ops.manager", "OpsManager#2026");
        for (String url : List.of("/api/incidents", "/api/incidents/export",
                "/api/metrics/kpi", "/api/metrics/trend", "/api/metrics/top-categories",
                "/api/metrics/matrix", "/api/metrics/sla-buckets")) {
            MvcResult result = mockMvc.perform(get(url).param("scope", "bogus")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(url).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).as(url)
                    .contains("invalid_filter");
        }
    }

    // ----------------------------------------------------------------------------- TM04

    @Test
    @DisplayName("TM04 viewer sees the three menus; exec and wallboard see none of the new ones")
    void menusAndAuthorization() throws Exception {
        Map<String, Object> viewer = loginBody("viewer", "Viewer#2026");
        assertThat(menuIds(viewer)).contains("incident.ops", "incident.problem", "incident.sr");
        for (Map<String, Object> menu : menus(viewer)) {
            if (menu.get("menuId").toString().matches("incident\\.(ops|problem|sr)")) {
                assertThat(menu.get("privileges")).as(menu.get("menuId") + " is view only")
                        .isEqualTo(List.of("view"));
            }
        }
        assertThat(menus(viewer).stream()
                .filter(menu -> "incident.ops".equals(menu.get("menuId")))
                .map(menu -> menu.get("label")).findFirst()).contains("Incident");

        for (String[] account : new String[][] {{"exec.user", "ExecUser#2026"},
                {"wallboard.user", "Wallboard#2026"}}) {
            assertThat(menuIds(loginBody(account[0], account[1])))
                    .as(account[0])
                    .doesNotContain("incident.ops", "incident.problem", "incident.sr");
        }
        assertThat(menuIds(loginBody("ops.manager", "OpsManager#2026")))
                .contains("incident.problem", "incident.sr");
        assertThat(menuIds(loginBody("admin.test", "AdminTest#2026")))
                .contains("incident.problem", "incident.sr");

        String wallboard = login("wallboard.user", "Wallboard#2026");
        assertThat(status("/api/incidents", wallboard)).isEqualTo(403);
        assertThat(status("/api/incidents?scope=PROBLEM", wallboard)).isEqualTo(403);
        assertThat(status("/api/metrics/matrix", wallboard)).isEqualTo(403);
        assertThat(status("/api/metrics/scope-counts", wallboard)).isEqualTo(200);
        assertThat(status("/api/metrics/wallboard", wallboard)).isEqualTo(200);

        String exec = login("exec.user", "ExecUser#2026");
        assertThat(status("/api/incidents?scope=SERVICE_REQUEST", exec)).isEqualTo(403);
        assertThat(status("/api/metrics/scope-counts", exec)).isEqualTo(200);
        assertThat(status("/api/metrics/wallboard", exec)).isEqualTo(200);

        String viewerToken = (String) viewer.get("token");
        assertThat(status("/api/incidents?scope=PROBLEM", viewerToken)).isEqualTo(200);
        assertThat(status("/api/metrics/kpi?scope=SERVICE_REQUEST", viewerToken)).isEqualTo(200);
        assertThat(status("/api/metrics/definitions", viewerToken)).isEqualTo(200);
        assertThat(status("/api/freshness", viewerToken)).isEqualTo(200);
        assertThat(status("/api/incidents/export?scope=PROBLEM", viewerToken))
                .as("export keeps its own privilege").isEqualTo(403);
        String ops = login("ops.manager", "OpsManager#2026");
        assertThat(status("/api/incidents/export?scope=PROBLEM", ops)).isEqualTo(200);
    }

    // ----------------------------------------------------------------------------- TM05

    @Test
    @DisplayName("TM05 V10 seeds scope groups, menus and grants; its seed bumps the generation once")
    void v10SeedsScopeGroupsMenusAndGrants() throws IOException {
        Map<Long, String> groups = new LinkedHashMap<>();
        jdbc.query("SELECT TRACKER_ID, SCOPE_GROUP FROM CFG_TRACKER_SCOPE ORDER BY TRACKER_ID",
                rs -> {
                    groups.put(rs.getLong(1), rs.getString(2));
                });
        assertThat(groups).containsEntry(7L, "INCIDENT").containsEntry(41L, "PROBLEM")
                .containsEntry(16L, "SERVICE_REQUEST").containsEntry(32L, "SERVICE_REQUEST")
                .containsEntry(33L, "SERVICE_REQUEST");

        assertThat(jdbc.queryForObject(
                "SELECT LABEL FROM APP_MENU WHERE MENU_ID = 'incident.ops'", String.class))
                .isEqualTo("Incident");
        assertThat(jdbc.queryForMap("SELECT ROUTE, SORT_ORDER FROM APP_MENU "
                + "WHERE MENU_ID = 'incident.problem'")).containsEntry("ROUTE", "/problem")
                .containsEntry("SORT_ORDER", java.math.BigDecimal.valueOf(11));
        assertThat(jdbc.queryForMap("SELECT ROUTE, SORT_ORDER FROM APP_MENU "
                + "WHERE MENU_ID = 'incident.sr'")).containsEntry("ROUTE", "/service-requests")
                .containsEntry("SORT_ORDER", java.math.BigDecimal.valueOf(12));

        // The grants of each role on the new menus are exactly its incident.ops grants.
        for (String menu : List.of("incident.problem", "incident.sr")) {
            assertThat(grants(menu)).as(menu + " mirrors incident.ops")
                    .isEqualTo(grants("incident.ops"));
            assertThat(grants(menu)).doesNotContainKeys("EXEC", "WALLBOARD");
        }
        assertThat(grants("incident.sr")).containsOnlyKeys("ADMIN", "OPS_MANAGER", "VIEWER");

        long grantsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM APP_ROLE_PRIVILEGE", Long.class);
        long before = generation();
        jdbc.execute(v10SeedBlock());
        assertThat(generation())
                .as("one config change, one re-ETL -- not one per trigger-fired row")
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APP_ROLE_PRIVILEGE", Long.class))
                .as("re-running the seed (repair) adds no grants")
                .isEqualTo(grantsBefore);
    }

    // ----------------------------------------------------------------------------- TM07

    @Test
    @DisplayName("TM07 matrix missingCount equals the matrixMissing table total and the export")
    void matrixMissingCountOpensExactlyThatManyRows() throws Exception {
        seedDimensions();
        // Complete.
        insertFact(1, 7, OPEN, Map.of("IMPACT_SEVERITY", "'High'", "URGENCY_SEVERITY", "'High'"));
        // Missing urgency, missing impact, both missing, blank impact (Oracle: '' is NULL),
        // and an enum id that resolves to no label (name null, id set).
        insertFact(2, 7, OPEN, Map.of("IMPACT_SEVERITY", "'High'"));
        insertFact(3, 7, OPEN, Map.of("URGENCY_SEVERITY", "'Low'"));
        insertFact(4, 7, OPEN, Map.of());
        insertFact(5, 7, CLOSED, Map.of("IMPACT_SEVERITY", "''", "URGENCY_SEVERITY", "'Low'"));
        insertFact(6, 7, OPEN, Map.of("IMPACT_SEVERITY_ID", "9", "URGENCY_SEVERITY", "'Low'"));
        // Cancelled work is outside the population and must not be counted as missing.
        insertFact(7, 7, CANCELLED, Map.of());
        // Another scope: not in the Incident matrix.
        insertFact(8, 41, OPEN, Map.of());

        String token = login("ops.manager", "OpsManager#2026");
        Map<String, Object> matrix = getObject("/api/metrics/matrix", token,
                "scope", "INCIDENT");
        long missing = number(matrix, "missingCount");

        assertThat(missing).isEqualTo(5);
        assertThat(number(getObject("/api/incidents", token, "scope", "INCIDENT",
                "matrixMissing", "true"), "totalElements"))
                .as("the 'N missing' link opens the same N")
                .isEqualTo(missing);
        assertThat(number(getObject("/api/incidents/export", token, "scope", "INCIDENT",
                "matrixMissing", "true"), "rowCount")).isEqualTo(missing);
        assertThat(missing)
                .isLessThanOrEqualTo(list(matrix, "cells").stream()
                        .mapToLong(cell -> number(cell, "count")).sum());
        // The Unknown / Unclassified cells are still real cells.
        assertThat(list(matrix, "cells")).extracting(cell -> cell.get("impact"))
                .contains("Unclassified", "Unknown (9)");
        // Without the scope the other tracker's row is missing too.
        assertThat(number(getObject("/api/metrics/matrix", token), "missingCount"))
                .isEqualTo(missing + 1);
    }

    @Test
    @DisplayName("TM07 the mart's literal 'Unclassified' / 'Unknown (id)' count as missing, and "
            + "tiles, charts and table all agree under matrixMissing=true")
    void literalPlaceholdersAreMissingAndEveryAggregateHonorsTheFilter() throws Exception {
        seedDimensions();
        insertFact(1, 7, OPEN, Map.of("IMPACT_SEVERITY", "'High'", "URGENCY_SEVERITY", "'High'"));
        // What MartRefreshUseCase really writes: literals, never NULL.
        insertFact(2, 7, OPEN, Map.of("IMPACT_SEVERITY", "'Unclassified'",
                "URGENCY_SEVERITY", "'High'"));
        insertFact(3, 7, CLOSED, Map.of("IMPACT_SEVERITY", "'High'",
                "URGENCY_SEVERITY", "'Unknown (9)'", "CLOSED_ON", "SYSDATE - 1",
                "LAST_DATE_STATUS", "SYSDATE - 1"));
        insertFact(4, 7, OPEN, Map.of("IMPACT_SEVERITY", "'Unclassified'",
                "URGENCY_SEVERITY", "'Unclassified'"));

        String token = login("ops.manager", "OpsManager#2026");
        Map<String, Object> matrix = getObject("/api/metrics/matrix", token, "scope", "INCIDENT",
                "matrixMissing", "true");
        long missing = number(matrix, "missingCount");
        assertThat(missing).isEqualTo(3);
        assertThat(list(matrix, "cells").stream().mapToLong(cell -> number(cell, "count")).sum())
                .as("the matrix itself ignores matrixMissing: every cell stays").isEqualTo(4);

        long table = number(getObject("/api/incidents", token, "scope", "INCIDENT",
                "matrixMissing", "true"), "totalElements");
        assertThat(table).isEqualTo(missing);
        assertThat(number(getObject("/api/incidents/export", token, "scope", "INCIDENT",
                "matrixMissing", "true"), "rowCount")).isEqualTo(missing);

        Map<String, Object> kpi = getObject("/api/metrics/kpi", token, "scope", "INCIDENT",
                "matrixMissing", "true");
        assertThat(number(kpi, "open") + number(kpi, "closed"))
                .as("KPI tiles == table total under matrixMissing").isEqualTo(table);
        assertThat(getList("/api/metrics/sla-buckets", token, "scope", "INCIDENT",
                "matrixMissing", "true").stream().mapToLong(b -> number(b, "count")).sum())
                .isEqualTo(table);
        assertThat(getList("/api/metrics/trend", token, "scope", "INCIDENT",
                "matrixMissing", "true").stream().mapToLong(p -> number(p, "created")).sum())
                .isEqualTo(table);
        assertThat(getList("/api/metrics/top-categories", token, "scope", "INCIDENT",
                "matrixMissing", "true").stream().mapToLong(c -> number(c, "count")).sum())
                .isEqualTo(table);
    }

    // ----------------------------------------------------------------------------- TM08

    @Test
    @DisplayName("TM08 neither the API row nor the export carries AGING_INCIDENT")
    void agingIncidentIsGone() throws Exception {
        seedDimensions();
        insertFact(1, 7, OPEN, Map.of());
        String token = login("ops.manager", "OpsManager#2026");

        assertThat(content(getObject("/api/incidents", token))).isNotEmpty()
                .allSatisfy(row -> assertThat(row.keySet())
                        .doesNotContain("AGING_INCIDENT").contains("AGING_NEW"));
        assertThat(content(getObject("/api/incidents/export", token))).isNotEmpty()
                .allSatisfy(row -> assertThat(row.keySet())
                        .doesNotContain("AGING_INCIDENT").contains("AGING_NEW"));
        assertThat(status("/api/incidents?sort=AGING_INCIDENT", token))
                .as("no longer a sort column").isEqualTo(400);
    }

    // -------------------------------------------------------------------------- fixtures

    private void seedDimensions() {
        jdbc.update("DELETE FROM EXPORT_AUDIT");
        insertStatus(20, "Open", 0);
        insertStatus(5, "Closed", 1);
        insertStatus(6, "Drop", 1);
        insertTracker(7, "Incident");
        insertTracker(41, "Problem");
        insertTracker(16, "SR - Infra");
        insertTracker(32, "SR - BAU");
        insertTracker(33, "SR - Patch");
        insertPriority(4, "Low");
    }

    /** Every group populated, with open, closed and a cancelled row that must stay out. */
    private void seedMixedTrackers() {
        seedDimensions();
        long id = 100;
        for (int tracker : new int[] {7, 7, 7, 41, 41, 16, 32, 33}) {
            insertFact(id++, tracker, OPEN, Map.of());
        }
        for (int tracker : new int[] {7, 41, 32}) {
            insertFact(id++, tracker, CLOSED, Map.of("CLOSED_ON", "SYSDATE - 1",
                    "LAST_DATE_STATUS", "SYSDATE - 1"));
        }
        insertFact(id, 7, CANCELLED, Map.of());
    }

    private void insertFact(long id, int tracker, String status, Map<String, String> extra) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("NO_INCIDENT", Long.toString(id));
        columns.put("TRACKER_ID", Integer.toString(tracker));
        columns.put("SUBJECT", "'incident " + id + "'");
        columns.put("DATE_CREATE", "SYSDATE - 3");
        columns.put("CREATE_MONTH", "TO_CHAR(SYSDATE - 3, 'YYYYMM')");
        columns.put("STATUS_ID", status);
        columns.put("PRIORITY_ID", "4");
        columns.put("SLA_DAYS", "60");
        columns.put("HAS_CLOSE_DATE_ROW", "0");
        columns.put("HAS_RESOLUTION_ROW", "0");
        columns.put("LAST_DATE_STATUS", "SYSDATE - 3");
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

    // ----------------------------------------------------------------------------- config

    private long generation() {
        return jdbc.queryForObject("SELECT GENERATION FROM CFG_VERSION", Long.class);
    }

    /** role -> privileges held on the menu, sorted. */
    private Map<String, List<String>> grants(String menu) {
        Map<String, List<String>> grants = new LinkedHashMap<>();
        jdbc.query("SELECT ROLE_ID, PRIVILEGE FROM APP_ROLE_PRIVILEGE WHERE MENU_ID = ? "
                        + "ORDER BY ROLE_ID, PRIVILEGE",
                rs -> {
                    grants.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>())
                            .add(rs.getString(2));
                }, menu);
        return grants;
    }

    private static String v10SeedBlock() throws IOException {
        String migration;
        try (InputStream in = ScopeIT.class.getResourceAsStream(
                "/db/migration/V10__scoped_menus.sql")) {
            assertThat(in).as("V10 must be on the classpath").isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int begin = migration.indexOf("-- V10-SEED-BEGIN");
        int end = migration.indexOf("-- V10-SEED-END");
        assertThat(begin).isNotNegative();
        assertThat(end).isGreaterThan(begin);
        String block = migration.substring(migration.indexOf('\n', begin) + 1, end).strip();
        assertThat(block).endsWith("/");
        return block.substring(0, block.length() - 1).strip();
    }

    // ----------------------------------------------------------------------------- http

    private Map<String, Object> loginBody(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + username).isEqualTo(200);
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private String login(String username, String password) throws Exception {
        return (String) loginBody(username, password).get("token");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> menus(Map<String, Object> login) {
        return (List<Map<String, Object>>) login.get("menus");
    }

    private static List<String> menuIds(Map<String, Object> login) {
        return menus(login).stream().map(menu -> menu.get("menuId").toString()).toList();
    }

    private int status(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private MvcResult perform(String url, String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(url + " body was " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    private Map<String, Object> getObject(String url, String token, String... params)
            throws Exception {
        return objectMapper.readValue(perform(url, token, params).getResponse()
                        .getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private List<Map<String, Object>> getList(String url, String token, String... params)
            throws Exception {
        return objectMapper.readValue(perform(url, token, params).getResponse()
                        .getContentAsString(),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    private static List<Map<String, Object>> content(Map<String, Object> body) {
        return list(body, "content");
    }

    private static long number(Map<String, Object> body, String field) {
        Object value = body.get(field);
        assertThat(value).as(field + " is present and numeric").isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}
