package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
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
 * Task 19 — the aggregates.
 *
 * <p>Three things are under test, and none of them is a happy path:
 *
 * <ol>
 *   <li><b>The charts and the table count the same population.</b> Every aggregate runs
 *       through {@code IncidentQueryUseCase.predicate}, so the matrix cell total is asserted
 *       against a {@code COUNT(*)} from that same builder. Two predicates would let a KPI card
 *       and the table beneath it report different totals for one filter.
 *   <li><b>Nothing silently leaves an aggregate.</b> Rows with no impact or urgency value, and
 *       rows whose enum id resolves to no label, land in real {@code Unclassified} /
 *       {@code Unknown (…)} cells. Cancelled work is excluded from backlog AND from the aging
 *       means, by the one documented rule, and comes back when the stage is named.
 *   <li><b>The comparison window is derived from the request.</b> Two different window lengths
 *       are asserted, so a hardcoded "previous month" (or a hardcoded 30 days) cannot pass.
 * </ol>
 *
 * <p>The detection-latency invariant is asserted in {@code MetricsContractTest} — it is a
 * property of the contract, not of any data, and belongs in the tier that runs without Oracle.
 */
@AutoConfigureMockMvc
class MetricsIT extends AbstractOracleIT {

    private static final String OPS = "ops.manager";
    private static final String OPS_PASSWORD = "OpsManager#2026";

    /** Mapped by V2: 20 → New (kept), 5 → Closed (kept), 6 → Cancelled (excluded). */
    private static final long OPEN_STATUS = 20;
    private static final long CLOSED_STATUS = 5;
    private static final long CANCELLED_STATUS = 6;

    /**
     * PTR: open in Redmine ({@code is_closed = 0}) but closed for reporting by V9's
     * {@code CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE = 1}.
     */
    private static final long PTR_STATUS = 11;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --------------------------------------------------------------------------------- KPI

    @Test
    @DisplayName("the KPI row counts open, breached and at-risk over one population")
    void kpiCountsOpenBreachedAndAtRisk() throws Exception {
        seedDimensions();
        // SLA 10 days throughout, so the ratio is readable straight off the age.
        insertFact(1001, OPEN_STATUS, 10, "SYSDATE - 2", Map.of());     // 20% consumed
        insertFact(1002, OPEN_STATUS, 10, "SYSDATE - 9", Map.of());     // 90% -- at risk
        insertFact(1003, OPEN_STATUS, 10, "SYSDATE - 20", Map.of());    // over -- breached
        insertFact(1004, CLOSED_STATUS, 10, "SYSDATE - 3", Map.of(
                "CLOSE_DATE", "TRUNC(SYSDATE) - 1", "HAS_CLOSE_DATE_ROW", "1"));

        Map<String, Object> kpi = getObject("/api/metrics/kpi", token());

        assertThat(number(kpi, "backlog"))
                .as("the KPI population is the table's population")
                .isEqualTo(4);
        assertThat(number(kpi, "open")).isEqualTo(3);
        assertThat(number(kpi, "breached")).isEqualTo(1);
        assertThat(number(kpi, "atRisk"))
                .as("at risk is 80% of the SLA consumed AND not already breached -- counting "
                        + "the breached row here would double-count it in both cards")
                .isEqualTo(1);
    }

    /**
     * The headline of this dataset. A dashboard that publishes ONE mean time to restore lets a
     * 2-day workaround stand in for a permanent fix that has not landed at all — which is
     * exactly the population {@code workaroundDebt} counts.
     */
    @Test
    @DisplayName("both mean-times are reported, and the workaround debt between them")
    void reportsBothMttrsAndTheWorkaroundDebtBetweenThem() throws Exception {
        seedDimensions();
        // Workaround at day 2, permanent fix at day 6.
        insertFact(1001, CLOSED_STATUS, 30, "TRUNC(SYSDATE) - 10", Map.of(
                "RESOLUTION_DATE", "TRUNC(SYSDATE) - 8",
                "CLOSE_DATE", "TRUNC(SYSDATE) - 4",
                "HAS_CLOSE_DATE_ROW", "1",
                "HAS_RESOLUTION_ROW", "1"));
        // Workaround at day 4, still owing a permanent fix: the debt.
        insertFact(1002, OPEN_STATUS, 30, "TRUNC(SYSDATE) - 10", Map.of(
                "RESOLUTION_DATE", "TRUNC(SYSDATE) - 6",
                "HAS_RESOLUTION_ROW", "1"));

        Map<String, Object> kpi = getObject("/api/metrics/kpi", token());

        assertThat(decimal(kpi, "mttrWorkaroundDays"))
                .as("mean time to the WORKAROUND, over both incidents that got one")
                .isEqualTo(3.0);
        assertThat(decimal(kpi, "mttrPermanentDays"))
                .as("mean time to the PERMANENT fix -- only 1001 has one")
                .isEqualTo(6.0);
        assertThat(decimal(kpi, "mttrGapDays"))
                .as("the gap between the two is the insight; reporting either alone hides it")
                .isEqualTo(3.0);
        assertThat(number(kpi, "workaroundDebt"))
                .as("a resolution date with no close date -- derived on read, never stored")
                .isEqualTo(1);
    }

    /**
     * A hardcoded "previous calendar month" passes a single-window test and is wrong for every
     * other window, so two lengths are asserted here.
     */
    @Test
    @DisplayName("the previous period mirrors the requested window length, at any length")
    void previousPeriodMirrorsTheRequestedWindowLength() throws Exception {
        seedDimensions();
        insertFact(2001, OPEN_STATUS, 30, "DATE '2026-06-02'", Map.of());
        insertFact(2002, OPEN_STATUS, 30, "DATE '2026-06-05'", Map.of());
        insertFact(2003, OPEN_STATUS, 30, "DATE '2026-06-09'", Map.of());
        insertFact(2004, OPEN_STATUS, 30, "DATE '2026-06-12'", Map.of());
        insertFact(2005, OPEN_STATUS, 30, "DATE '2026-06-19'", Map.of());

        String token = token();

        // A 10-day window: 11th-20th compares against the 1st-10th.
        Map<String, Object> tenDays = getObject("/api/metrics/kpi", token,
                "dateFrom", "2026-06-11", "dateTo", "2026-06-20");
        assertThat(number(tenDays, "windowDays"))
                .as("both bounds are inclusive: 11th to 20th is ten days, not nine")
                .isEqualTo(10);
        assertThat(number(tenDays, "backlog")).isEqualTo(2);

        Map<String, Object> tenBefore = previous(tenDays);
        assertThat(tenBefore.get("windowFrom")).isEqualTo("2026-06-01");
        assertThat(tenBefore.get("windowTo")).isEqualTo("2026-06-10");
        assertThat(number(tenBefore, "windowDays"))
                .as("the comparison window is the SAME LENGTH as the requested one")
                .isEqualTo(10);
        assertThat(number(tenBefore, "backlog")).isEqualTo(3);

        // A 7-day window over the same data: a hardcoded month or a hardcoded 30 days fails
        // here even if it happened to satisfy the case above.
        Map<String, Object> sevenDays = getObject("/api/metrics/kpi", token,
                "dateFrom", "2026-06-14", "dateTo", "2026-06-20");
        assertThat(number(sevenDays, "windowDays")).isEqualTo(7);
        assertThat(number(sevenDays, "backlog")).isEqualTo(1);

        Map<String, Object> sevenBefore = previous(sevenDays);
        assertThat(sevenBefore.get("windowFrom")).isEqualTo("2026-06-07");
        assertThat(sevenBefore.get("windowTo")).isEqualTo("2026-06-13");
        assertThat(number(sevenBefore, "windowDays")).isEqualTo(7);
        assertThat(number(sevenBefore, "backlog")).isEqualTo(2);
        assertThat(sevenBefore.get("previousPeriod"))
                .as("one level of comparison, not an infinite regress of them")
                .isNull();

        // And an unbounded window has no defensible predecessor -- omitted, never invented.
        assertThat(getObject("/api/metrics/kpi", token).get("previousPeriod")).isNull();
    }

    // ------------------------------------------------------------------------------ matrix

    /**
     * A row that falls outside every cell disappears from the heatmap while still sitting in
     * the backlog — the under-reported queue nobody staffs for.
     */
    @Test
    @DisplayName("the matrix gives Unclassified and Unknown real cells, and its cells sum to "
            + "the filtered population")
    void matrixIncludesUnclassifiedAndUnknownAsRealCells() throws Exception {
        seedDimensions();
        insertFact(3001, OPEN_STATUS, 10, "SYSDATE - 1", Map.of(
                "IMPACT_SEVERITY", "'High'", "IMPACT_SEVERITY_ID", "1",
                "URGENCY_SEVERITY", "'High'", "URGENCY_SEVERITY_ID", "1"));
        // Impact known, urgency never filled in at all.
        insertFact(3002, OPEN_STATUS, 10, "SYSDATE - 1", Map.of(
                "IMPACT_SEVERITY", "'High'", "IMPACT_SEVERITY_ID", "1"));
        // An enum id with no label row: unresolved, NOT absent.
        insertFact(3003, OPEN_STATUS, 10, "SYSDATE - 1", Map.of("IMPACT_SEVERITY_ID", "9"));
        // Cancelled: out of the matrix by the SAME rule that keeps it out of the backlog.
        insertFact(3004, CANCELLED_STATUS, 10, "SYSDATE - 1", Map.of(
                "IMPACT_SEVERITY", "'Low'", "IMPACT_SEVERITY_ID", "2",
                "URGENCY_SEVERITY", "'Low'", "URGENCY_SEVERITY_ID", "2"));

        String token = token();
        Map<String, Object> matrix = getObject("/api/metrics/matrix", token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) matrix.get("cells");

        assertThat(cells).extracting(cell -> cell.get("impact") + "/" + cell.get("urgency"))
                .as("an unpopulated axis is a REAL cell; a null axis is dropped by every "
                        + "charting library and the row vanishes from the heatmap")
                .containsExactlyInAnyOrder("High/High", "High/Unclassified",
                        "Unknown (9)/Unclassified");

        long cellTotal = cells.stream().mapToLong(cell -> number(cell, "count")).sum();
        long filteredCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM V_INCIDENT WHERE EXCLUDE_FROM_BACKLOG = 0", Long.class);
        assertThat(cellTotal)
                .as("cells sum to the population of the SAME filter predicate -- cancelled "
                        + "work excluded by the same rule the table uses")
                .isEqualTo(filteredCount)
                .isEqualTo(3);
        // V9 (plan TM017): the Backlog tile is retired in favour of Open + Closed, which
        // partition the same unfaceted population -- the equality is unchanged in substance.
        assertThat(cellTotal)
                .as("and to Open + Closed the KPI cards publish, because one builder produces "
                        + "both sides of this equality")
                .isEqualTo(openPlusClosed(getObject("/api/metrics/kpi", token)));
    }

    // -------------------------------------------------------------- cancelled work is out

    @Test
    @DisplayName("cancelled incidents are out of the backlog and out of the aging means")
    void cancelledIncidentsAreExcludedFromBacklogAndAging() throws Exception {
        seedDimensions();
        insertFact(4001, OPEN_STATUS, 10, "SYSDATE - 5", Map.of());
        // 100 days old, closed yesterday: it would dominate every mean it reached.
        insertFact(4002, CANCELLED_STATUS, 10, "TRUNC(SYSDATE) - 100", Map.of(
                "CLOSE_DATE", "TRUNC(SYSDATE) - 1", "HAS_CLOSE_DATE_ROW", "1",
                "RESOLUTION_DATE", "TRUNC(SYSDATE) - 2", "HAS_RESOLUTION_ROW", "1"));

        String token = token();
        Map<String, Object> kpi = getObject("/api/metrics/kpi", token);

        assertThat(number(kpi, "backlog"))
                .as("a dropped ticket is not work anyone still owes")
                .isEqualTo(1);
        assertThat(number(kpi, "breached"))
                .as("a 99-day cancelled ticket would otherwise report as a breach")
                .isEqualTo(0);
        assertThat(kpi.get("mttrPermanentDays"))
                .as("no surviving incident has a close date, so the mean is absent -- not a 99")
                .isNull();
        assertThat(kpi.get("mttrWorkaroundDays")).isNull();
        assertThat(number(kpi, "workaroundDebt")).isEqualTo(0);

        // Excluded by default, NOT unreachable: naming the stage brings it back.
        Map<String, Object> cancelled = getObject("/api/metrics/kpi", token,
                "stage", "Cancelled");
        assertThat(number(cancelled, "backlog"))
                .as("the exclusion is a default, not a deletion")
                .isEqualTo(1);
        assertThat(number(cancelled, "breached")).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- trend

    @Test
    @DisplayName("the trend reports created, closed and a CARRIED backlog per month")
    void trendReportsCreatedClosedAndBacklogPerMonth() throws Exception {
        seedDimensions();
        // Closed in a different month than it was raised -- the case a single GROUP BY
        // DATE_CREATE gets wrong, drawing a burn-down that never burns down.
        insertFact(5001, CLOSED_STATUS, 60, "DATE '2026-01-10'", Map.of(
                "CLOSE_DATE", "DATE '2026-02-15'", "HAS_CLOSE_DATE_ROW", "1"));
        insertFact(5002, OPEN_STATUS, 60, "DATE '2026-01-20'", Map.of());
        insertFact(5003, CLOSED_STATUS, 60, "DATE '2026-02-05'", Map.of(
                "CLOSE_DATE", "DATE '2026-02-20'", "HAS_CLOSE_DATE_ROW", "1"));

        List<Map<String, Object>> trend = getList("/api/metrics/trend", token(),
                "granularity", "month");

        assertThat(trend).extracting(period -> period.get("period"))
                .containsExactly("2026-01", "2026-02");
        assertThat(number(trend.get(0), "created")).isEqualTo(2);
        assertThat(number(trend.get(0), "closed")).isEqualTo(0);
        assertThat(number(trend.get(0), "backlog")).isEqualTo(2);

        assertThat(number(trend.get(1), "created")).isEqualTo(1);
        assertThat(number(trend.get(1), "closed"))
                .as("a closure belongs to the month it happened, not the month the incident "
                        + "was raised")
                .isEqualTo(2);
        assertThat(number(trend.get(1), "backlog"))
                .as("backlog CARRIES: 2 - 0 then + 1 - 2 = 1, not a per-period difference")
                .isEqualTo(1);
    }

    // ------------------------------------------------------ sla buckets / top categories

    @Test
    @DisplayName("the SLA buckets cover the same population, unmeasurable ages included")
    void slaBucketsCoverTheSameFilteredPopulation() throws Exception {
        seedDimensions();
        insertFact(6001, OPEN_STATUS, 10, "SYSDATE - 1", Map.of());     // 0-50%
        insertFact(6002, OPEN_STATUS, 10, "SYSDATE - 6", Map.of());     // 50-80%
        insertFact(6003, OPEN_STATUS, 10, "SYSDATE - 9", Map.of());     // 80-100%
        insertFact(6004, OPEN_STATUS, 10, "SYSDATE - 40", Map.of());    // Breached
        // A close-date row whose value never parsed: the age is not measurable, and it gets
        // its own band rather than dropping out of the chart.
        insertFact(6005, OPEN_STATUS, 10, "SYSDATE - 3", Map.of("HAS_CLOSE_DATE_ROW", "1"));

        String token = token();
        List<Map<String, Object>> buckets = getList("/api/metrics/sla-buckets", token);

        assertThat(buckets).extracting(bucket -> bucket.get("bucket"))
                .as("in band order, and 'Unknown' is a band -- not a missing row")
                .containsExactly("0-50%", "50-80%", "80-100%", "Breached", "Unknown");
        assertThat(buckets.stream().mapToLong(bucket -> number(bucket, "count")).sum())
                .as("every incident in the filtered population is in exactly one band")
                // V9 (plan TM017): Open + Closed replaces the retired Backlog tile.
                .isEqualTo(openPlusClosed(getObject("/api/metrics/kpi", token)))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("top categories rank by count and compare against the derived window")
    void topCategoriesCompareAgainstTheSameDerivedWindow() throws Exception {
        seedDimensions();
        // Requested window: 11th-20th. Derived comparison: 1st-10th, same length.
        insertFact(7001, OPEN_STATUS, 30, "DATE '2026-06-12'",
                Map.of("CATEGORY_INCIDENT", "'Network'"));
        insertFact(7002, OPEN_STATUS, 30, "DATE '2026-06-13'",
                Map.of("CATEGORY_INCIDENT", "'Network'"));
        insertFact(7003, OPEN_STATUS, 30, "DATE '2026-06-14'",
                Map.of("CATEGORY_INCIDENT", "'Network'"));
        insertFact(7004, OPEN_STATUS, 30, "DATE '2026-06-15'",
                Map.of("CATEGORY_INCIDENT", "'Database'"));
        insertFact(7005, OPEN_STATUS, 30, "DATE '2026-06-16'", Map.of());
        insertFact(7006, OPEN_STATUS, 30, "DATE '2026-06-03'",
                Map.of("CATEGORY_INCIDENT", "'Network'"));
        insertFact(7007, OPEN_STATUS, 30, "DATE '2026-06-04'",
                Map.of("CATEGORY_INCIDENT", "'Network'"));

        List<Map<String, Object>> top = getList("/api/metrics/top-categories", token(),
                "dateFrom", "2026-06-11", "dateTo", "2026-06-20", "limit", "10");

        assertThat(top).extracting(row -> row.get("category"))
                .as("an incident with no category is a real row, not a dropped one")
                .containsExactly("Network", "Database", "Unclassified");
        assertThat(number(top.get(0), "count")).isEqualTo(3);
        assertThat(decimal(top.get(0), "deltaPct"))
                .as("3 now against 2 in the preceding window of the SAME length")
                .isEqualTo(50.0);
        assertThat(top.get(1).get("deltaPct"))
                .as("null, not 0: no baseline and unchanged are different facts")
                .isNull();
    }


    // ------------------------------------------------------------ V9: Open + Closed, facets

    /** AC4.2: the Backlog tile is gone from the UI, but the field stays and still reconciles. */
    @Test
    @DisplayName("with no facets, open + closed == backlog, and backlog is still served")
    void openPlusClosedEqualsBacklog() throws Exception {
        seedDimensions();
        insertFact(8001, OPEN_STATUS, 10, "SYSDATE - 2", Map.of());
        insertFact(8002, OPEN_STATUS, 10, "SYSDATE - 20", Map.of());
        insertFact(8003, CLOSED_STATUS, 10, "SYSDATE - 5", Map.of(
                "CLOSED_ON", "SYSDATE - 1"));
        // Closed by the override, not by Redmine: it must land in Closed, not in Open.
        insertFact(8004, PTR_STATUS, 10, "SYSDATE - 6", Map.of(
                "LAST_DATE_STATUS", "SYSDATE - 2"));
        // Cancelled: out of the population on BOTH sides of the equality.
        insertFact(8005, CANCELLED_STATUS, 10, "SYSDATE - 3", Map.of());

        Map<String, Object> kpi = getObject("/api/metrics/kpi", token());

        assertThat(kpi).as("backlog is deprecated, not removed").containsKey("backlog");
        assertThat(number(kpi, "open")).isEqualTo(2);
        assertThat(number(kpi, "closed"))
                .as("the PTR override counts as closed")
                .isEqualTo(2);
        assertThat(openPlusClosed(kpi))
                .as("Open and Closed partition the unfaceted population")
                .isEqualTo(number(kpi, "backlog"))
                .isEqualTo(4);
    }

    /** AC1.6: a PTR closure is on the trend at its STOP_AT, and agrees with the Closed tile. */
    @Test
    @DisplayName("the trend counts an override-closed ticket as closed on its stop date")
    void trendCountsOverrideClosedTicketsOnStopDate() throws Exception {
        seedDimensions();
        // PTR, raised in January, moved to PTR in February. No close date, no CLOSED_ON.
        insertFact(9001, PTR_STATUS, 60, "DATE '2026-01-10'", Map.of(
                "LAST_DATE_STATUS", "TIMESTAMP '2026-02-12 10:00:00'"));
        // Redmine-closed in March with no cf105 row: stops at CLOSED_ON.
        insertFact(9002, CLOSED_STATUS, 60, "DATE '2026-01-20'", Map.of(
                "CLOSED_ON", "TIMESTAMP '2026-03-03 09:00:00'",
                "LAST_DATE_STATUS", "TIMESTAMP '2026-03-03 09:00:00'"));
        // Still open: created, never closed.
        insertFact(9003, OPEN_STATUS, 60, "DATE '2026-01-25'", Map.of());

        String token = token();
        List<Map<String, Object>> trend = getList("/api/metrics/trend", token,
                "granularity", "month");

        assertThat(trend).extracting(period -> period.get("period"))
                .containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(number(trend.get(0), "created")).isEqualTo(3);
        assertThat(number(trend.get(0), "closed")).isEqualTo(0);
        assertThat(number(trend.get(1), "closed"))
                .as("the PTR ticket closes in the month its status changed, although Redmine "
                        + "still calls it open")
                .isEqualTo(1);
        assertThat(number(trend.get(2), "closed"))
                .as("a Redmine closure with no close-date row stops at CLOSED_ON")
                .isEqualTo(1);
        assertThat(number(trend.get(2), "backlog")).isEqualTo(1);

        long trendClosed = trend.stream().mapToLong(period -> number(period, "closed")).sum();
        assertThat(trendClosed)
                .as("the trend's closures are the Closed tile's population")
                .isEqualTo(number(getObject("/api/metrics/kpi", token), "closed"));
    }

    /**
     * AC3.2: with both facets on, each ACTIVE tile equals the table total, and the tiles of
     * the other dimension are narrowed by the active facet rather than zeroed by their own.
     */
    @Test
    @DisplayName("with both facets active, each active tile equals the table total and the "
            + "other tiles stay meaningful")
    void facetedCountsKeepOtherFacetTilesMeaningful() throws Exception {
        seedDimensions();
        // SLA 10 throughout.
        insertFact(10001, OPEN_STATUS, 10, "SYSDATE - 20", Map.of());   // open, breached
        insertFact(10002, OPEN_STATUS, 10, "SYSDATE - 30", Map.of());   // open, breached
        insertFact(10003, OPEN_STATUS, 10, "SYSDATE - 9", Map.of());    // open, at risk
        insertFact(10004, OPEN_STATUS, 10, "SYSDATE - 1", Map.of());    // open, on track
        insertFact(10005, CLOSED_STATUS, 10, "TRUNC(SYSDATE) - 40", Map.of(
                "CLOSE_DATE", "TRUNC(SYSDATE) - 20", "HAS_CLOSE_DATE_ROW", "1")); // closed late
        insertFact(10006, CLOSED_STATUS, 10, "TRUNC(SYSDATE) - 5", Map.of(
                "CLOSE_DATE", "TRUNC(SYSDATE) - 4", "HAS_CLOSE_DATE_ROW", "1"));  // closed in time

        String token = token();

        // Breached active, then Open clicked: both facets apply.
        Map<String, Object> both = getObject("/api/metrics/kpi", token,
                "sla", "BREACHED", "status", "OPEN");
        long tableTotal = totalElements(getObject("/api/incidents", token,
                "sla", "BREACHED", "status", "OPEN"));

        assertThat(tableTotal).isEqualTo(2);
        assertThat(number(both, "open"))
                .as("the active Open tile equals the table total")
                .isEqualTo(tableTotal);
        assertThat(number(both, "breached"))
                .as("the active Breached tile equals the table total")
                .isEqualTo(tableTotal);
        assertThat(number(both, "atRisk"))
                .as("At Risk is counted within Open -- faceted by status, not zeroed by the "
                        + "sla facet it does not belong to")
                .isEqualTo(1);
        assertThat(number(both, "closed"))
                .as("Closed is narrowed by the Breached facet")
                .isEqualTo(1);

        // Breached alone: its tile equals the table total, and Open/Closed split it.
        Map<String, Object> breached = getObject("/api/metrics/kpi", token, "sla", "BREACHED");
        long breachedTotal = totalElements(getObject("/api/incidents", token,
                "sla", "BREACHED"));
        assertThat(number(breached, "breached")).isEqualTo(breachedTotal).isEqualTo(3);
        assertThat(number(breached, "open") + number(breached, "closed"))
                .isEqualTo(breachedTotal);
        assertThat(number(breached, "atRisk"))
                .as("no status facet: At Risk is the whole population's")
                .isEqualTo(1);
    }

    /** The comparison window differs from the current one in its dates and in nothing else. */
    @Test
    @DisplayName("the previous period applies the same status and sla facets")
    void previousPeriodAppliesSameFacets() throws Exception {
        seedDimensions();
        // Current window 11th-20th.
        insertFact(11001, OPEN_STATUS, 5, "DATE '2026-06-12'", Map.of());          // breached
        insertFact(11002, OPEN_STATUS, 999, "DATE '2026-06-13'", Map.of());        // on track
        insertFact(11003, CLOSED_STATUS, 5, "DATE '2026-06-12'", Map.of(
                "CLOSE_DATE", "DATE '2026-06-19'", "HAS_CLOSE_DATE_ROW", "1"));   // late
        // Previous window 1st-10th.
        insertFact(11004, OPEN_STATUS, 5, "DATE '2026-06-02'", Map.of());          // breached
        insertFact(11005, OPEN_STATUS, 999, "DATE '2026-06-03'", Map.of());        // on track
        insertFact(11006, CLOSED_STATUS, 5, "DATE '2026-06-04'", Map.of(
                "CLOSE_DATE", "DATE '2026-06-05'", "HAS_CLOSE_DATE_ROW", "1"));   // in time

        String token = token();
        String[] window = {"dateFrom", "2026-06-11", "dateTo", "2026-06-20"};

        Map<String, Object> unfaceted = previous(getObject("/api/metrics/kpi", token, window));
        assertThat(number(unfaceted, "open")).isEqualTo(2);
        assertThat(number(unfaceted, "closed")).isEqualTo(1);
        assertThat(number(unfaceted, "breached")).isEqualTo(1);

        Map<String, Object> slaFaceted = previous(getObject("/api/metrics/kpi", token,
                "dateFrom", "2026-06-11", "dateTo", "2026-06-20", "sla", "BREACHED"));
        assertThat(slaFaceted.get("windowFrom")).isEqualTo("2026-06-01");
        assertThat(number(slaFaceted, "open"))
                .as("the previous Open tile is narrowed by the same sla facet")
                .isEqualTo(1);
        assertThat(number(slaFaceted, "closed"))
                .as("the previous window's only closure was in time, so Breached excludes it")
                .isEqualTo(0);

        Map<String, Object> statusFaceted = previous(getObject("/api/metrics/kpi", token,
                "dateFrom", "2026-06-11", "dateTo", "2026-06-20", "status", "CLOSED"));
        assertThat(number(statusFaceted, "breached"))
                .as("the previous Breached tile is narrowed by the same status facet")
                .isEqualTo(0);
        assertThat(number(statusFaceted, "atRisk")).isEqualTo(0);
    }

    // ----------------------------------------------------------------------------- helpers

    private String token() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", OPS, "password", OPS_PASSWORD))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login for " + OPS).isEqualTo(200);
        return (String) objectMapper.readValue(result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                }).get("token");
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
    private static Map<String, Object> previous(Map<String, Object> kpi) {
        Object previous = kpi.get("previousPeriod");
        assertThat(previous)
                .as("a bounded window always carries its comparison")
                .isNotNull();
        return (Map<String, Object>) previous;
    }

    private static long number(Map<String, Object> body, String field) {
        Object value = body.get(field);
        assertThat(value).as(field + " is present and numeric").isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    /** The two tiles that replaced the Backlog tile; together they partition the population. */
    private static long openPlusClosed(Map<String, Object> kpi) {
        return number(kpi, "open") + number(kpi, "closed");
    }

    private static long totalElements(Map<String, Object> page) {
        return number(page, "totalElements");
    }

    private static double decimal(Map<String, Object> body, String field) {
        Object value = body.get(field);
        assertThat(value).as(field + " is present and numeric").isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }

    // ---------------------------------------------------------------------------- fixtures

    private void seedDimensions() {
        insertStatus(OPEN_STATUS, "Open", 0);
        insertStatus(CLOSED_STATUS, "Closed", 1);
        insertStatus(CANCELLED_STATUS, "Drop", 1);
        insertStatus(PTR_STATUS, "PTR", 0);
        insertTracker(7, "Incident");
        insertPriority(4, "Low");
    }

    /**
     * One fact row. {@code extra} carries raw SQL EXPRESSIONS for the columns a given test
     * cares about — dates as {@code DATE '…'} or {@code TRUNC(SYSDATE) - n}, labels quoted —
     * so a test can state "no close date row" and "a close date row whose value never parsed"
     * as different fixtures, which is the distinction {@code HAS_CLOSE_DATE_ROW} exists for.
     */
    private void insertFact(long id, long statusId, int slaDays, String createExpr,
            Map<String, String> extra) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("NO_INCIDENT", Long.toString(id));
        columns.put("TRACKER_ID", "7");
        columns.put("SUBJECT", "'incident " + id + "'");
        columns.put("DATE_CREATE", createExpr);
        columns.put("CREATE_MONTH", "TO_CHAR(" + createExpr + ", 'YYYYMM')");
        columns.put("STATUS_ID", Long.toString(statusId));
        columns.put("PRIORITY_ID", "4");
        columns.put("SLA_DAYS", Integer.toString(slaDays));
        columns.put("HAS_CLOSE_DATE_ROW", "0");
        columns.put("HAS_RESOLUTION_ROW", "0");
        columns.put("LAST_DATE_STATUS", createExpr);
        columns.put("ETL_UPDATED_AT", "SYSDATE");
        columns.put("SOURCE_UPDATED_ON", "SYSDATE");
        columns.putAll(extra);

        jdbcTemplate.update("INSERT INTO INCIDENT_FACT (" + String.join(", ", columns.keySet())
                + ") VALUES (" + String.join(", ", columns.values()) + ")");
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
