package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.IncidentRowDto;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Task 16 — the bulk PII egress path.
 *
 * <p>Two things are under test here and neither is a happy path:
 *
 * <ol>
 *   <li><b>The DTO cannot drift from {@code V_INCIDENT}.</b> Its field set is generated from
 *       the Oracle catalog, exactly the way {@code MartRefreshRepository} generates its MERGE,
 *       and {@link #DECLARED_VIEW_COLUMNS} is the tripwire that forces a view change to be
 *       deliberate rather than silent.
 *   <li><b>Neither egress path is unbounded.</b> {@code /api/incidents} clamps {@code size} to
 *       {@value IncidentFilter#MAX_PAGE_SIZE}; {@code /api/incidents/export} caps at
 *       {@code incident.export.max-rows}, flags {@code truncated} and writes exactly one
 *       {@code EXPORT_AUDIT} row per download.
 * </ol>
 *
 * <p>{@code incident.export.max-rows} is lowered to 3 so the cap can be crossed with a handful
 * of fixture rows instead of 50,001.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "incident.export.max-rows=3")
class IncidentQueryIT extends AbstractOracleIT {

    private static final String OPS = "ops.manager";
    private static final String OPS_PASSWORD = "OpsManager#2026";

    /**
     * {@code V_INCIDENT}'s column set, transcribed once, as {@code MartSchemaIT} transcribes
     * {@code INCIDENT_FACT}'s.
     *
     * <p>This is NOT a second source of truth for the DTO — the DTO reads the catalog. It is a
     * tripwire: adding or dropping a view column changes what every export carries, and that
     * has to fail a test rather than surface in a stakeholder's workbook.
     */
    private static final List<String> DECLARED_VIEW_COLUMNS = List.of(
            // -- INCIDENT_FACT, passed through by the inner block
            "NO_INCIDENT", "TRACKER_ID", "SUBJECT", "DATE_CREATE", "CREATE_MONTH",
            "UPDATED_ON", "CLOSED_ON",
            "STATUS_ID", "PRIORITY_ID", "SLA_DAYS", "PARENT_ID", "DONE_RATIO",
            "AUTHOR_ID", "AUTHOR", "ASSIGNED_TO_ID", "ASSIGNED_TO",
            "DEVELOPER_ASSIGNED_ID", "DEVELOPER_ASSIGNED",
            "TYPE_OF_INCIDENT", "TYPE_OF_INCIDENT_ID",
            "CATEGORY_INCIDENT", "CATEGORY_INCIDENT_ID",
            "EXTERNAL_INTERNAL", "EXTERNAL_INTERNAL_ID",
            "IMPACT_SEVERITY", "IMPACT_SEVERITY_ID",
            "URGENCY_SEVERITY", "URGENCY_SEVERITY_ID",
            "INSTITUSI", "INSTITUSI_ID",
            "CLOSE_DATE", "RESOLUTION_DATE", "MIGRATION_DATE",
            "HAS_CLOSE_DATE_ROW", "HAS_RESOLUTION_ROW", "HAS_DATE_REJECT", "SOLUTION",
            "LAST_STATUS", "LAST_DATE_STATUS", "FIRST_STATUS_CHANGE_AT",
            "WORKAROUND", "ROOTCAUSE",
            "ETL_UPDATED_AT", "SOURCE_UPDATED_ON", "CV_DIGEST", "CFG_GENERATION",
            "SLA_AT_RISK_DAYS",   // V9: fact column, the at-risk cushion
            // -- inner block: live dimension joins and the one shared elapsed expression
            "SLA", "STATUS", "IS_CLOSED", "TRACKER", "PRIORITY", "STATUS_STAGE",
            "EXCLUDE_FROM_BACKLOG", "AGING_INCIDENT", "AGING_LAST_STATUS", "MTTA_HOURS",
            "REDMINE_IS_CLOSED",  // V9: Redmine's own flag, before the override
            "STOP_AT", "CLOSE_BASIS",  // V9: the close clock
            "ELAPSED_DAYS", "TTR_WORKAROUND_DAYS", "TTR_PERMANENT_DAYS",
            // -- outer block: report renderings and the SLA bands derived from ELAPSED_DAYS
            "RPT_DATE_CREATE", "RPT_CREATE_DATE", "RPT_UPDATED_ON", "RPT_CLOSED_ON",
            "AGING_NEW", "SLA_RATIO", "SLA_BREACHED", "SLA_BUCKET",
            "SLA_AT_RISK");       // V9

    /**
     * The 34 report fields that reach the client (the delivered workbook's 35 minus
     * {@code AGING_INCIDENT}, dropped in Phase 2), by the name they reach the client
     * under. {@code RPT_*} where the workbook wants the rendered string rather than the native
     * timestamp — see the {@code RPT_} comment block in {@code V3__mart.sql}.
     *
     * <p>A column dropped from the DTO fails HERE rather than in a user's export.
     */
    private static final List<String> REPORT_COLUMNS = List.of(
            "NO_INCIDENT", "SUBJECT", "RPT_DATE_CREATE", "RPT_CREATE_DATE", "RPT_UPDATED_ON",
            "RPT_CLOSED_ON", "STATUS", "STATUS_STAGE", "TRACKER", "PRIORITY", "SLA",
            "AGING_NEW", "AGING_LAST_STATUS", "SOLUTION", "CLOSE_DATE",
            "RESOLUTION_DATE", "MIGRATION_DATE", "TYPE_OF_INCIDENT", "CATEGORY_INCIDENT",
            "EXTERNAL_INTERNAL", "IMPACT_SEVERITY", "URGENCY_SEVERITY", "INSTITUSI",
            "AUTHOR", "AUTHOR_ID", "ASSIGNED_TO", "ASSIGNED_TO_ID", "DEVELOPER_ASSIGNED",
            "WORKAROUND", "ROOTCAUSE", "LAST_STATUS", "LAST_DATE_STATUS", "PARENT_ID",
            "DONE_RATIO");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --------------------------------------------------------------- the DTO/view contract

    /**
     * The DTO's fields come from {@code USER_TAB_COLUMNS}, not from a hand-typed field list —
     * the same catalog-driven approach {@code MartRefreshRepository.buildMerge()} uses. A
     * hand-typed DTO drifts silently: the view keeps a column, the export quietly stops
     * carrying it, and nobody notices until a stakeholder diffs the workbook.
     */
    @Test
    @DisplayName("the DTO's fields are generated from V_INCIDENT's catalog columns")
    void dtoFieldsAreDrivenByTheViewCatalog() {
        List<String> catalog = viewColumns();

        assertThat(catalog)
                .as("V_INCIDENT's catalog columns must match what V3__mart.sql declares; "
                        + "changing the view changes every export and must be deliberate")
                .containsExactlyInAnyOrderElementsOf(DECLARED_VIEW_COLUMNS);

        List<String> exposed = useCase(IncidentQueryUseCase.class).columns();

        List<String> expected = new ArrayList<>(catalog);
        expected.removeAll(IncidentRowDto.INTERNAL_COLUMNS);
        assertThat(exposed)
                .as("every view column except the ETL's own bookkeeping is exposed, and it is "
                        + "READ from the catalog rather than retyped here")
                .containsExactlyInAnyOrderElementsOf(expected);

        assertThat(exposed)
                .as("ETL bookkeeping is not report data and must never leave the service")
                .doesNotContainAnyElementsOf(IncidentRowDto.INTERNAL_COLUMNS);

        // TM021: the close clock and At Risk reach the table and the export; the raw Redmine
        // flag and the cushion are inputs to them, not report data.
        assertThat(exposed)
                .as("V9's report columns are exported")
                .contains("STOP_AT", "CLOSE_BASIS", "SLA_AT_RISK")
                .doesNotContain("REDMINE_IS_CLOSED", "SLA_AT_RISK_DAYS");

        // TM08: AGING_INCIDENT stays on the VIEW (not rewritten) but is not API/export data.
        assertThat(catalog).contains("AGING_INCIDENT");
        assertThat(exposed).doesNotContain("AGING_INCIDENT");
        assertThat(IncidentFilter.SORTABLE_COLUMNS).doesNotContain("AGING_INCIDENT");

        // The sort allowlist is only meaningful if every name in it is a real view column.
        assertThat(catalog)
                .as("an allowlisted sort column that does not exist is an ORA-00904 waiting "
                        + "for the first client that sorts by it")
                .containsAll(IncidentFilter.SORTABLE_COLUMNS);
    }

    @Test
    @DisplayName("every report column reaches the client on GET /api/incidents")
    void returnsEveryReportColumn() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);

        Map<String, Object> page = getJson("/api/incidents", token());
        List<Map<String, Object>> content = content(page);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).keySet())
                .as("a dropped report column must fail here, not in a user's export")
                .containsAll(REPORT_COLUMNS)
                .doesNotContain("AGING_INCIDENT");   // TM08
    }

    // ------------------------------------------------------------------------- filtering

    @Test
    @DisplayName("filters compose, and every one of them is a bound parameter")
    void filtersCompose() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);
        insertFact(1002, 16, 20, 5L, 7, "SYSDATE - 5", "Bank B", "Network", null);
        insertFact(1003, 7, 20, 5L, 7, "SYSDATE - 1", "Bank A", "Database", null);

        String token = token();
        // Default order is DATE_CREATE DESC, so the newest in-scope incident leads.
        assertThat(ids(getJson("/api/incidents", token, "tracker", "Incident")))
                .containsExactly(1003, 1001);
        assertThat(ids(getJson("/api/incidents", token, "tracker", "Incident",
                "priority", "High"))).containsExactly(1003);
        assertThat(ids(getJson("/api/incidents", token, "tracker", "Incident",
                "priority", "High", "institusi", "Bank A", "category", "Database")))
                .containsExactly(1003);
        // ...and one term that excludes everything still returns an empty page, not everything.
        assertThat(ids(getJson("/api/incidents", token, "tracker", "Incident",
                "institusi", "Bank B"))).isEmpty();

        // A value that would be SQL if it were concatenated instead of bound.
        Map<String, Object> injected =
                getJson("/api/incidents", token, "institusi", "Bank A' OR '1'='1");
        assertThat(injected.get("totalElements"))
                .as("a bound parameter matches nothing; a concatenated one returns every row")
                .isEqualTo(0);
    }

    /**
     * Oracle has no empty string: {@code value = ''} IS {@code value = NULL} and is never
     * true. The MySQL source's {@code x IS NULL OR x = ''} ported literally evaluates to false
     * on every row — see {@code artifacts/oracle-empty-clob-finding.md}.
     */
    @Test
    @DisplayName("an empty-locator CLOB counts as absent, via DBMS_LOB.GETLENGTH not = ''")
    void anEmptyClobIsTreatedAsAbsent() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", "'a workaround'");
        insertFact(1002, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", "EMPTY_CLOB()");
        insertFact(1003, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);

        String token = token();
        assertThat(ids(getJson("/api/incidents?hasWorkaround=true", token)))
                .containsExactly(1001);
        assertThat(ids(getJson("/api/incidents?hasWorkaround=false", token)))
                .as("the zero-length locator is NOT NULL, so an IS NULL test alone misses it")
                .containsExactly(1002, 1003);
    }

    @Test
    @DisplayName("cancelled incidents are absent by default and reachable when asked for")
    void excludesCancelledFromBacklogButKeepsThemReachable() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);
        insertFact(1004, 7, 6, 4L, 60, "SYSDATE - 3", "Bank A", "Network", null);

        String token = token();
        assertThat(ids(getJson("/api/incidents", token)))
                .as("EXCLUDE_FROM_BACKLOG = 1 is out of the default view")
                .containsExactly(1001);
        assertThat(ids(getJson("/api/incidents?stage=Cancelled", token)))
                .as("...but reachable when the Cancelled stage is explicitly requested")
                .containsExactly(1004);
    }

    // --------------------------------------------------------------- sorting and paging

    @Test
    @DisplayName("paging is stable because NO_INCIDENT is always the final sort key")
    void sortsAndPagesDeterministically() throws Exception {
        seedDimensions();
        // Identical DATE_CREATE and identical STATUS: without the appended tie-break the
        // order is undefined and page 2 silently repeats a row from page 1.
        insertFact(1001, 7, 20, 4L, 60, "TIMESTAMP '2026-03-04 09:00:00'", "Bank A", "Net",
                null);
        insertFact(1002, 7, 20, 4L, 60, "TIMESTAMP '2026-03-04 09:00:00'", "Bank A", "Net",
                null);
        insertFact(1003, 7, 20, 4L, 60, "TIMESTAMP '2026-03-04 09:00:00'", "Bank A", "Net",
                null);

        String token = token();
        List<Integer> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Map<String, Object> body =
                    getJson("/api/incidents?sort=STATUS&direction=ASC&size=1&page=" + page,
                            token);
            assertThat(body.get("totalElements")).isEqualTo(3);
            assertThat(body.get("totalPages")).isEqualTo(3);
            paged.addAll(ids(body));
        }
        assertThat(paged)
                .as("three single-row pages must cover the set exactly once")
                .containsExactly(1001, 1002, 1003);

        assertThat(ids(getJson("/api/incidents?sort=NO_INCIDENT&direction=DESC", token)))
                .containsExactly(1003, 1002, 1001);
    }

    /**
     * The sort column arrives from the client and lands in {@code ORDER BY}, so it is checked
     * against an enumerated allowlist. A silent fallback to a default hides both a client bug
     * and an injection attempt behind identical behaviour.
     */
    @ParameterizedTest
    @ValueSource(strings = {"; DROP", "NO_INCIDENT; --", "(SELECT 1)", "SUBJECT",
            "DATE_CREATE)--"})
    @DisplayName("an unknown sort column is a 400, never a silent fallback")
    void rejectsAnUnknownSortColumn(String sort) throws Exception {
        String token = token();
        MvcResult result = mockMvc.perform(get("/api/incidents").param("sort", sort)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("sort=" + sort + " body was " + result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .as("the refusal must not echo the view or the SQL back")
                .doesNotContain("V_INCIDENT")
                .doesNotContain("ORDER BY");

        // Direction is allowlisted on the same argument.
        mockMvc.perform(get("/api/incidents").param("direction", "ASC; DROP")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }

    /**
     * An unbounded {@code size} is a full-dataset export through the query endpoint, which
     * bypasses the {@code export} privilege entirely. It is CLAMPED rather than refused: a 400
     * on an over-large page teaches clients nothing, while a clamp bounds the egress and
     * reports the size actually served.
     */
    @Test
    @DisplayName("a request for more than 200 rows is clamped to 200")
    void oversizedPageRequestIsClamped() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);

        Map<String, Object> body = getJson("/api/incidents?size=100000", token());
        assertThat(body.get("size"))
                .as("the served page size, clamped -- not the size the caller asked for")
                .isEqualTo(IncidentFilter.MAX_PAGE_SIZE);
        assertThat(content(body)).hasSizeLessThanOrEqualTo(IncidentFilter.MAX_PAGE_SIZE);
    }

    /**
     * Five entirely ordinary client mistakes used to come back as 500s, because
     * {@code MethodArgumentTypeMismatchException} was missing from
     * {@code GlobalExceptionHandler}'s 400 bucket and fell through to the catch-all. Each one
     * also logged at ERROR as an <i>unhandled</i> exception, so the error log could not
     * distinguish a genuine fault from someone editing a URL by hand.
     *
     * <p>{@code 2026-02-31} is in here deliberately: it is a well-formed date in a real calendar
     * that does not exist in it, and it fails at conversion rather than at parse.
     */
    @ParameterizedTest
    @CsvSource({
            "dateFrom,not-a-date",
            "dateFrom,2026-02-31",
            "dateTo,31-12-2026",
            "size,abc",
            "page,two",
            "hasWorkaround,maybe"})
    @DisplayName("an unparseable query parameter is a 400, not a 500")
    void rejectsUnparseableParameters(String name, String value) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/incidents").param(name, value)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as(name + "=" + value + " body was " + result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .as("a 4xx body still discloses no schema")
                .doesNotContain("V_INCIDENT")
                .doesNotContain("ORA-");
    }

    /**
     * The far end of the calendar was a 500 with a distinct cause worth keeping separate from
     * the parse failures above. The query binds the upper bound as {@code dateTo + 1 day}, so
     * {@code 9999-12-31} became {@code 10000-01-01} on the way to the driver and Oracle refused
     * it with {@code ORA-17268: Year value is out of range} — from a date the caller was
     * entitled to type. {@code IncidentFilter} now clamps to a representable date, so the
     * request SUCCEEDS rather than merely failing more politely.
     */
    @Test
    @DisplayName("a date past Oracle's range is clamped, not a 500")
    void clampsDatesBeyondOraclesRange() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);

        Map<String, Object> body =
                getJson("/api/incidents?dateFrom=0001-01-01&dateTo=9999-12-31", token());

        assertThat(content(body))
                .as("an unbounded window must still return the rows inside it")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------------- the export

    @Test
    @DisplayName("an export past the cap is truncated and says so")
    void exportIsCappedAndFlagsTruncation() throws Exception {
        seedDimensions();
        for (int i = 1; i <= 5; i++) {
            insertFact(2000 + i, 7, 20, 4L, 60, "SYSDATE - " + i, "Bank A", "Network", null);
        }

        Map<String, Object> body = getJson("/api/incidents/export", token());
        assertThat(body.get("rowCount"))
                .as("the cap is what bounds the one bulk egress path in the system")
                .isEqualTo(3);
        assertThat(body.get("truncated"))
                .as("a capped export and a complete one are different events")
                .isEqualTo(true);
        assertThat(body.get("maxRows")).isEqualTo(3);
        assertThat(content(body)).hasSize(3);

        Map<String, Object> audit = onlyAuditRow();
        assertThat(audit.get("TRUNCATED")).isEqualTo("Y");
        assertThat(((Number) audit.get("ROW_COUNT")).intValue()).isEqualTo(3);
    }

    /**
     * Bulk egress is audited, not logged. The audit row is written by the same use-case call
     * that produces the payload, so there is no path that yields rows without recording them.
     */
    @Test
    @DisplayName("every export writes exactly one EXPORT_AUDIT row")
    void everyExportWritesAnAuditRow() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 60, "SYSDATE - 10", "Bank A", "Network", null);
        insertFact(1002, 7, 20, 4L, 60, "SYSDATE - 5", "Bank A", "Network", null);

        Map<String, Object> body = getJson("/api/incidents/export", token(),
                "tracker", "Incident", "institusi", "Bank A");
        assertThat(body.get("rowCount")).isEqualTo(2);
        assertThat(body.get("truncated")).isEqualTo(false);
        assertThat((String) body.get("exportId")).isNotBlank();

        Map<String, Object> audit = onlyAuditRow();
        assertThat(audit.get("EXPORT_ID")).isEqualTo(body.get("exportId"));
        assertThat(audit.get("USERNAME"))
                .as("who pulled it -- the audit is useless without this")
                .isEqualTo(OPS);
        assertThat(((Number) audit.get("ROW_COUNT")).intValue()).isEqualTo(2);
        assertThat(audit.get("TRUNCATED")).isEqualTo("N");
        assertThat((String) audit.get("FILTER_JSON"))
                .as("the whole filter set is what makes a pull reconstructable afterwards")
                .contains("Incident")
                .contains("Bank A");
        assertThat(audit.get("REQUESTED_AT")).isNotNull();

        // A second export is a second event, not an update of the first.
        getJson("/api/incidents/export", token(), "tracker", "Incident");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM EXPORT_AUDIT",
                Integer.class)).isEqualTo(2);
    }


    // --------------------------------------------------------------- V9: status/sla facets

    /** AC3.1: clicking Breached narrows the table to exactly the count the tile showed. */
    @Test
    @DisplayName("the sla=BREACHED table total equals the KPI breached tile")
    void slaFacetTotalMatchesKpiTile() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 10, "SYSDATE - 20", "Bank A", "Network", null); // breached
        insertFact(1002, 7, 20, 4L, 10, "SYSDATE - 2", "Bank A", "Network", null);  // on track
        insertFact(1003, 7, 5, 4L, 10, "TRUNC(SYSDATE) - 30", "Bank A", "Network", null);
        closeOn(1003, "TRUNC(SYSDATE) - 1");                                        // late close
        insertFact(1004, 7, 5, 4L, 10, "TRUNC(SYSDATE) - 3", "Bank A", "Network", null);
        closeOn(1004, "TRUNC(SYSDATE) - 1");                                        // in time

        String token = token();
        Map<String, Object> page = getJson("/api/incidents", token, "sla", "BREACHED");
        Map<String, Object> kpi = getJson("/api/metrics/kpi", token);

        assertThat(ids(page))
                .as("a late closure is still a breach; the table carries open and closed alike")
                .containsExactlyInAnyOrder(1001, 1003);
        assertThat(((Number) page.get("totalElements")).longValue())
                .as("the tile count and the table total are the same number")
                .isEqualTo(((Number) kpi.get("breached")).longValue());

        // The status facet partitions the same way.
        assertThat(ids(getJson("/api/incidents", token, "status", "OPEN")))
                .containsExactlyInAnyOrder(1001, 1002);
        assertThat(ids(getJson("/api/incidents", token, "status", "CLOSED")))
                .containsExactlyInAnyOrder(1003, 1004);
        assertThat(ids(getJson("/api/incidents", token, "status", "CLOSED", "sla", "BREACHED")))
                .containsExactly(1003);
    }

    /** AC3.5: a facet outside its enum is refused, with a code the client can branch on. */
    @ParameterizedTest
    @CsvSource({
            "/api/incidents,status,MAYBE",
            "/api/incidents,sla,NOPE",
            "/api/incidents,sla,breached",
            "/api/incidents/export,status,MAYBE",
            "/api/metrics/kpi,sla,NOPE",
            "/api/metrics/trend,status,MAYBE"})
    @DisplayName("an unknown status or sla facet value is a 400 invalid_filter")
    void invalidFacetValueIs400(String url, String field, String value) throws Exception {
        MvcResult result = mockMvc.perform(get(url).param(field, value)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andReturn();

        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as(url + "?" + field + "=" + value + " body was " + raw)
                .isEqualTo(400);
        Map<String, Object> body = json(result);
        assertThat(body.get("code")).isEqualTo("invalid_filter");
        assertThat(body.get("field")).isEqualTo(field);
        assertThat(body.get("status")).isEqualTo(400);
        assertThat((String) body.get("correlationId")).isNotBlank();
        assertThat(raw)
                .as("the refusal discloses no schema")
                .doesNotContain("V_INCIDENT")
                .doesNotContain("ORA-")
                .doesNotContain("SLA_BREACHED")
                .doesNotContain("IS_CLOSED");
    }

    /** AC3.6: the export is the table, facets included -- and the audit records them. */
    @Test
    @DisplayName("the export applies the status and sla facets")
    void exportAppliesStatusAndSlaFacets() throws Exception {
        seedDimensions();
        insertFact(1001, 7, 20, 4L, 10, "SYSDATE - 20", "Bank A", "Network", null); // open, breached
        insertFact(1002, 7, 20, 4L, 10, "SYSDATE - 9", "Bank A", "Network", null);  // open, at risk
        insertFact(1003, 7, 20, 4L, 10, "SYSDATE - 1", "Bank A", "Network", null);  // open
        insertFact(1004, 7, 5, 4L, 10, "TRUNC(SYSDATE) - 30", "Bank A", "Network", null);
        closeOn(1004, "TRUNC(SYSDATE) - 1");                                        // closed, breached

        String token = token();
        Map<String, Object> atRisk = getJson("/api/incidents/export", token,
                "status", "OPEN", "sla", "AT_RISK");
        assertThat(ids(atRisk)).containsExactly(1002);
        assertThat(atRisk.get("rowCount")).isEqualTo(1);
        assertThat(atRisk.get("truncated")).isEqualTo(false);
        assertThat(content(atRisk).get(0))
                .as("the export carries the new report columns")
                .containsEntry("SLA_AT_RISK", 1)
                .containsEntry("CLOSE_BASIS", "OPEN")
                .containsKey("STOP_AT");
        assertThat((String) onlyAuditRow().get("FILTER_JSON"))
                .as("the audit trail must be able to reconstruct a faceted pull")
                .contains("OPEN")
                .contains("AT_RISK");

        jdbcTemplate.update("DELETE FROM EXPORT_AUDIT");
        Map<String, Object> breached = getJson("/api/incidents/export", token,
                "sla", "BREACHED");
        assertThat(ids(breached)).containsExactlyInAnyOrder(1001, 1004);
        assertThat(ids(getJson("/api/incidents", token, "sla", "BREACHED")))
                .as("the export and the table apply one predicate")
                .containsExactlyInAnyOrderElementsOf(ids(breached));
    }

    // ------------------------------------------------------------------------------ helpers

    private String token() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", OPS, "password", OPS_PASSWORD))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login for " + OPS).isEqualTo(200);
        return (String) json(result).get("token");
    }

    /**
     * @param params flat key/value pairs. Passed as real request parameters rather than
     *     embedded in the URL: {@code MockMvcRequestBuilders.get} re-encodes the template, so a
     *     {@code %20} in the path becomes a literal {@code %2520} and the filter silently
     *     matches nothing.
     */
    private Map<String, Object> getJson(String url, String token, String... params)
            throws Exception {
        MockHttpServletRequestBuilder request = get(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(url + " body was " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json(result);
    }

    private Map<String, Object> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> body) {
        Object content = body.get("content");
        assertThat(content).as("every list response carries a content array").isNotNull();
        return (List<Map<String, Object>>) content;
    }

    private static List<Integer> ids(Map<String, Object> body) {
        return content(body).stream()
                .map(row -> ((Number) row.get("NO_INCIDENT")).intValue())
                .toList();
    }

    /** The single audit row, asserted to be single. */
    private Map<String, Object> onlyAuditRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT EXPORT_ID, USERNAME, REQUESTED_AT, ROW_COUNT, TRUNCATED, "
                        + "TO_CHAR(FILTER_JSON) AS FILTER_JSON FROM EXPORT_AUDIT");
        assertThat(rows)
                .as("EXACTLY one row per export: zero is an untraceable pull, two is a "
                        + "double-count that makes the trail unreadable")
                .hasSize(1);
        Map<String, Object> row = new LinkedHashMap<>();
        rows.get(0).forEach((key, value) -> row.put(key.toUpperCase(Locale.ROOT), value));
        return row;
    }

    private List<String> viewColumns() {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'V_INCIDENT' "
                        + "ORDER BY COLUMN_ID", String.class);
    }

    // -------------------------------------------------------------------------- fixtures

    /**
     * Mirror dimension rows plus a clean {@code EXPORT_AUDIT}.
     *
     * <p>{@code AbstractOracleIT} truncates the mart and the mirror before each test but not
     * {@code EXPORT_AUDIT} — it is an audit trail, and a helper that truncated it by default
     * would be the wrong default everywhere else.
     */
    private void seedDimensions() {
        jdbcTemplate.update("DELETE FROM EXPORT_AUDIT");
        // Status 20 -> stage New (EXCLUDE_FROM_BACKLOG 0); status 6 -> Cancelled (1). Both
        // come from CFG_STATUS_STAGE_MAP, seeded by V2.
        insertStatus(20, "Open", 0);
        insertStatus(6, "Drop", 1);
        // Status 5 -> stage Closed (kept); Redmine-closed.
        insertStatus(5, "Closed", 1);
        insertTracker(7, "Incident");
        insertTracker(16, "SR - Infra");
        insertPriority(4, "Low");
        insertPriority(5, "High");
    }

    private void insertFact(long id, long trackerId, long statusId, Long priorityId,
            int slaDays, String createExpr, String institusi, String category,
            String workaroundExpr) {
        jdbcTemplate.update("INSERT INTO INCIDENT_FACT (NO_INCIDENT, TRACKER_ID, SUBJECT, "
                + "DATE_CREATE, CREATE_MONTH, STATUS_ID, PRIORITY_ID, SLA_DAYS, INSTITUSI, "
                + "CATEGORY_INCIDENT, WORKAROUND, HAS_CLOSE_DATE_ROW, LAST_DATE_STATUS, "
                + "ETL_UPDATED_AT, SOURCE_UPDATED_ON) VALUES ("
                + id + ", " + trackerId + ", 'incident " + id + "', "
                + createExpr + ", TO_CHAR(" + createExpr + ", 'YYYYMM'), "
                + statusId + ", " + (priorityId == null ? "NULL" : priorityId) + ", "
                + slaDays + ", '" + institusi + "', '" + category + "', "
                + (workaroundExpr == null ? "NULL" : workaroundExpr)
                + ", 0, " + createExpr + ", SYSDATE, SYSDATE)");
    }

    /** Stamps a Redmine closure: V9 stops the clock at CLOSED_ON when there is no cf105 row. */
    private void closeOn(long id, String closedOnExpr) {
        jdbcTemplate.update("UPDATE INCIDENT_FACT SET CLOSED_ON = " + closedOnExpr
                + ", LAST_DATE_STATUS = " + closedOnExpr + " WHERE NO_INCIDENT = " + id);
    }

    private void insertStatus(long id, String name, int isClosed) {
        jdbcTemplate.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("issue_statuses")
                + " (ID, NAME, IS_CLOSED, POSITION) VALUES ("
                + id + ", '" + name + "', " + isClosed + ", 1)");
    }

    private void insertTracker(long id, String name) {
        jdbcTemplate.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("trackers")
                + " (ID, NAME, POSITION) VALUES (" + id + ", '" + name + "', 1)");
    }

    private void insertPriority(long id, String name) {
        jdbcTemplate.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("enumerations")
                + " (ID, NAME, TYPE, POSITION, ACTIVE) VALUES ("
                + id + ", '" + name + "', 'IssuePriority', 1, 1)");
    }
}
