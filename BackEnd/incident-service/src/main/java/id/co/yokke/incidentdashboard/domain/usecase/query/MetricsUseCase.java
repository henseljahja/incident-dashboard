package id.co.yokke.incidentdashboard.domain.usecase.query;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase.Predicate;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.KpiResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.MatrixResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.MatrixResult;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.ScopeCountsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.WallboardResponse;
import id.co.yokke.incidentdashboard.domain.model.ScopeGroup;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.TrendResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Every aggregate behind {@code /api/metrics/**} (Task 19).
 *
 * <h2>Tiles follow the facets; charts do not</h2>
 *
 * <p>The KPI tiles apply the {@code status}/{@code sla} facets per tile ({@link #kpi}); the
 * charts (trend, top categories, matrix, SLA buckets) describe the PERIOD and use the base
 * predicate only, so a facet on a chart request is validated and then has no effect (plan
 * decision 2026-09-15, "faceted tiles").
 *
 * <h2>One filter predicate, not two</h2>
 *
 * <p>Nothing here builds a WHERE clause. Every query calls
 * {@link IncidentQueryUseCase#predicate(IncidentFilter)} — the SAME builder
 * {@code GET /api/incidents} and the export use. A second copy would drift the moment a filter
 * term changed on one side, and the failure is silent and expensive: the KPI card says 412 and
 * the table beneath it lists 380, for the same filter, and an operator has no way to tell
 * which is wrong. In particular the cancelled-work rule ({@code EXCLUDE_FROM_BACKLOG = 0}
 * unless the caller names the stage) is applied by that one builder, so the backlog count, the
 * aging means and the matrix cell totals cannot disagree about the population by construction.
 *
 * <h2>Reads the view, never the fact</h2>
 *
 * <p>All clock-derived columns — {@code SLA_RATIO}, {@code SLA_BREACHED}, {@code SLA_BUCKET},
 * {@code TTR_WORKAROUND_DAYS}, {@code TTR_PERMANENT_DAYS} — are {@code V_INCIDENT}'s, computed
 * from its ONE shared elapsed expression. Recomputing any of them here would be a second
 * definition of "breached".
 *
 * <h2>No detection-latency metric exists, and none can</h2>
 *
 * <p>The source records when a ticket was raised, never when the incident began. There is
 * therefore no detection-latency aggregate anywhere in this class or in the DTOs it returns,
 * and {@code MetricsContractTest} asserts that no field, label or identifier ever acquires
 * one. Publishing an invented one on an executive dashboard is worse than publishing nothing.
 *
 * <p><b>Nothing here logs a row.</b> Counts and durations only.
 */
@Service
@Lazy
public class MetricsUseCase {

    private static final Logger log = LoggerFactory.getLogger(MetricsUseCase.class);

    /** The ONLY read surface. Never {@code INCIDENT_FACT}. */
    private static final String VIEW = "V_INCIDENT";

    /**
     * At Risk is a VIEW column since V9: open, not breached, and no more calendar days left than
     * the priority's cushion ({@code CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS}). Referenced, never
     * recomputed here -- the old inline 80% ratio counted closed tickets and was a second
     * definition of the rule.
     */
    private static final String AT_RISK = "SLA_AT_RISK = 1";

    private static final String BREACHED = "SLA_BREACHED = 1";

    private static final String OPEN = "IS_CLOSED = 0";

    private static final String CLOSED = "IS_CLOSED = 1";

    /**
     * An unresolved dimension gets a REAL cell, never a {@code NULL} axis: a null label is
     * dropped by every charting library, and the row disappears from the heatmap while still
     * counting in the backlog. {@code Unknown (<id>)} mirrors {@code V_INCIDENT}'s convention
     * for an unresolved status or tracker.
     */
    private static final String IMPACT_CELL = cell("IMPACT_SEVERITY");

    private static final String URGENCY_CELL = cell("URGENCY_SEVERITY");

    private static final String CATEGORY_CELL = "NVL(CATEGORY_INCIDENT, 'Unclassified')";

    /**
     * Granularity → Oracle format model. An ALLOWLIST, because the format model is the one
     * piece of client-influenced text that reaches the SQL string; an unknown value throws
     * (400) rather than falling back, exactly as {@code IncidentFilter} treats a sort column.
     */
    private static final Map<String, String> GRANULARITIES = Map.of(
            "day", "YYYY-MM-DD",
            "week", "IYYY-\"W\"IW",
            "month", "YYYY-MM");

    public static final String DEFAULT_GRANULARITY = "month";

    /** Bounds the top-categories fan-out; a chart with 4,000 slices is a denial of service. */
    private static final int MAX_CATEGORY_LIMIT = 50;

    private final JdbcTemplate jdbc;

    /** Held for its predicate builder — see the class javadoc. */
    private final IncidentQueryUseCase incidents;

    public MetricsUseCase(JdbcTemplate jdbc, @Lazy IncidentQueryUseCase incidents) {
        this.jdbc = jdbc;
        this.incidents = incidents;
    }

    // ------------------------------------------------------------------------------ KPI

    /** The KPI row, plus the same figures over the preceding equal-length window. */
    public KpiResponse kpi(IncidentFilter filter) {
        KpiResponse current = kpiFor(filter);
        IncidentFilter previous = previousWindow(filter);
        return current.withPreviousPeriod(previous == null ? null : kpiFor(previous));
    }

    /**
     * ONE statement, over the base population, with a conditional sum per tile.
     *
     * <p>Each status tile applies the OTHER dimension's facet and never its own (see
     * {@link KpiResponse}): Open and Closed are narrowed by the {@code sla} facet, Breached and
     * At Risk by the {@code status} facet. The facet fragments are the very ones
     * {@link IncidentQueryUseCase#predicate} ANDs onto the table's WHERE, so an active tile and
     * the table total are the same count by construction. They bind nothing, so the statement's
     * parameters are exactly the base predicate's.
     */
    private KpiResponse kpiFor(IncidentFilter filter) {
        Predicate base = incidents.basePredicate(filter);
        String slaFacet = incidents.facetPredicate(null, filter.sla()).condition();
        String statusFacet = incidents.facetPredicate(filter.status(), null).condition();

        String sql = "SELECT COUNT(*) AS BACKLOG, "
                + tile(slaFacet, OPEN) + " AS OPEN_COUNT, "
                + tile(slaFacet, CLOSED) + " AS CLOSED_COUNT, "
                + tile(statusFacet, BREACHED) + " AS BREACHED, "
                + tile(statusFacet, AT_RISK) + " AS AT_RISK, "
                + "ROUND(AVG(TTR_WORKAROUND_DAYS), 2) AS MTTR_WORKAROUND, "
                + "ROUND(AVG(TTR_PERMANENT_DAYS), 2) AS MTTR_PERMANENT, "
                // Single MTTR: the Closed tile's population (closed, narrowed by the sla
                // facet), mean of the view's ELAPSED_DAYS (the V9 close clock).
                + "ROUND(AVG(CASE WHEN (" + slaFacet + ") AND " + CLOSED
                + " THEN ELAPSED_DAYS END), 2) AS MTTR_DAYS, "
                // The headline population: a workaround landed, the permanent fix has not.
                + "COUNT(CASE WHEN RESOLUTION_DATE IS NOT NULL AND CLOSE_DATE IS NULL "
                + "THEN 1 END) AS WORKAROUND_DEBT "
                + "FROM " + VIEW + base.sql();

        Map<String, Object> row = jdbc.queryForMap(sql, base.params().toArray());
        Double workaround = number(row.get("MTTR_WORKAROUND"));
        Double permanent = number(row.get("MTTR_PERMANENT"));
        Double gap = workaround == null || permanent == null
                ? null
                : round2(permanent - workaround);

        return new KpiResponse(filter.dateFrom(), filter.dateTo(), windowDays(filter),
                count(row.get("BACKLOG")), count(row.get("OPEN_COUNT")),
                count(row.get("CLOSED_COUNT")),
                count(row.get("BREACHED")), count(row.get("AT_RISK")),
                workaround, permanent, gap, number(row.get("MTTR_DAYS")),
                count(row.get("WORKAROUND_DEBT")), null);
    }

    /** A conditional count: the tile's own condition AND the facet it is narrowed by. */
    private static String tile(String facetCondition, String tileCondition) {
        return "SUM(CASE WHEN (" + facetCondition + ") AND " + tileCondition
                + " THEN 1 ELSE 0 END)";
    }

    /**
     * The window of the SAME LENGTH immediately before the requested one.
     *
     * <p>Derived from the request, never a calendar month: a 30-day window compares against
     * the preceding 30 days and a 7-day window against the preceding 7. Every other filter
     * term — the tile facets included — is carried over unchanged, so the comparison differs
     * from the current window in the dates and in nothing else.
     *
     * <p>{@code null} when either bound is open — an unbounded window has no defensible
     * predecessor, and inventing one puts a made-up delta on the dashboard.
     */
    private static IncidentFilter previousWindow(IncidentFilter filter) {
        Integer days = windowDays(filter);
        if (days == null) {
            return null;
        }
        LocalDate previousTo = filter.dateFrom().minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1L);
        return new IncidentFilter(previousFrom, previousTo, filter.trackers(), filter.stages(),
                filter.priorities(), filter.institusi(), filter.categories(),
                filter.hasWorkaround(), filter.status(), filter.sla(), filter.scope(),
                filter.matrixMissing(), filter.search(), filter.sources(), filter.assignees(),
                filter.page(), filter.size(), filter.sortColumn(), filter.sortDirection());
    }

    /** Inclusive of both bounds — a from == to request is one day, not zero. */
    private static Integer windowDays(IncidentFilter filter) {
        if (filter.dateFrom() == null || filter.dateTo() == null
                || filter.dateTo().isBefore(filter.dateFrom())) {
            return null;
        }
        return (int) (ChronoUnit.DAYS.between(filter.dateFrom(), filter.dateTo()) + 1);
    }

    // --------------------------------------------------------------------------- matrix

    /**
     * The impact/urgency matrix over the filtered population. Cells sum to
     * {@link #count(IncidentFilter)} — asserted, not assumed.
     */
    public MatrixResult matrix(IncidentFilter filter) {
        Predicate predicate = incidents.basePredicate(filter);
        Map<String, String> spo = new java.util.HashMap<>();
        List<String> impactOrder = new ArrayList<>();
        List<String> urgencyOrder = new ArrayList<>();
        jdbc.query("SELECT IMPACT, URGENCY, PRIORITY, IMPACT_SORT, URGENCY_SORT "
                        + "FROM CFG_PRIORITY_MATRIX ORDER BY IMPACT_SORT, URGENCY_SORT",
                rs -> {
                    spo.put(key(rs.getString("IMPACT"), rs.getString("URGENCY")),
                            rs.getString("PRIORITY"));
                    if (!impactOrder.contains(rs.getString("IMPACT"))) {
                        impactOrder.add(rs.getString("IMPACT"));
                    }
                });
        jdbc.query("SELECT URGENCY FROM CFG_PRIORITY_MATRIX GROUP BY URGENCY "
                        + "ORDER BY MIN(URGENCY_SORT)",
                rs -> {
                    urgencyOrder.add(rs.getString(1));
                });
        List<MatrixResponse> cells = jdbc.query("SELECT " + IMPACT_CELL + " AS IMPACT, " + URGENCY_CELL
                        + " AS URGENCY, COUNT(*) AS CELL_COUNT FROM " + VIEW + predicate.sql()
                        + " GROUP BY " + IMPACT_CELL + ", " + URGENCY_CELL
                        + " ORDER BY 1, 2",
                (rs, rowNum) -> new MatrixResponse(rs.getString("IMPACT"),
                        rs.getString("URGENCY"), rs.getLong("CELL_COUNT"),
                        spo.get(key(rs.getString("IMPACT"), rs.getString("URGENCY")))),
                predicate.params().toArray());

        // Same base population, narrowed by the SAME constant the table's matrixMissing filter
        // uses, so "N missing" opens exactly N rows.
        Predicate missing = incidents.basePredicate(filter)
                .and(new Predicate(" WHERE " + IncidentQueryUseCase.MATRIX_MISSING, List.of()));
        Long missingCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + VIEW + missing.sql(),
                Long.class, missing.params().toArray());
        return new MatrixResult(cells, missingCount == null ? 0L : missingCount,
                List.copyOf(impactOrder), List.copyOf(urgencyOrder));
    }

    /**
     * The population the matrix and the backlog are both drawn from — the SAME predicate the
     * table pages over, with the same cancelled-work rule. Public so the test can assert the
     * cell-total equality against the real thing rather than against a re-typed WHERE clause.
     */
    public long count(IncidentFilter filter) {
        Predicate predicate = incidents.basePredicate(filter);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + VIEW + predicate.sql(),
                Long.class, predicate.params().toArray());
        return total == null ? 0L : total;
    }

    // ---------------------------------------------------------------------- sla buckets

    /** The SLA bands, in band order rather than alphabetical. */
    public List<MatrixResponse.SlaBucket> slaBuckets(IncidentFilter filter) {
        Predicate predicate = incidents.basePredicate(filter);
        return jdbc.query("SELECT SLA_BUCKET, COUNT(*) AS BUCKET_COUNT FROM " + VIEW
                        + predicate.sql() + " GROUP BY SLA_BUCKET ORDER BY CASE SLA_BUCKET "
                        + "WHEN '0-50%' THEN 1 WHEN '50-80%' THEN 2 WHEN '80-100%' THEN 3 "
                        + "WHEN 'Breached' THEN 4 ELSE 5 END",
                (rs, rowNum) -> new MatrixResponse.SlaBucket(rs.getString("SLA_BUCKET"),
                        rs.getLong("BUCKET_COUNT")),
                predicate.params().toArray());
    }

    // ----------------------------------------------------------------------------- trend

    /**
     * Created / closed / carried backlog per period.
     *
     * <p>The two halves are separate passes over the SAME filtered population, unioned: a
     * period can close work it did not create, and a single {@code GROUP BY DATE_CREATE} would
     * attribute every closure to the month the incident was RAISED — which draws a burn-down
     * that never burns down.
     */
    public List<TrendResponse> trend(IncidentFilter filter, String granularity) {
        String format = format(granularity);
        Predicate predicate = incidents.basePredicate(filter);

        List<Object> params = new ArrayList<>(predicate.params());
        params.addAll(predicate.params());

        String sql = "SELECT PERIOD, SUM(CREATED) AS CREATED, SUM(CLOSED) AS CLOSED FROM ("
                + "SELECT TO_CHAR(DATE_CREATE, '" + format + "') AS PERIOD, 1 AS CREATED, "
                + "0 AS CLOSED FROM " + VIEW + predicate.sql()
                + " UNION ALL "
                + "SELECT TO_CHAR(c.CLOSED_AT, '" + format + "'), 0, 1 FROM ("
                // STOP_AT (V9) is already one DATE across its cf105 / CLOSED_ON / status-change
                // branches, so the old COALESCE(CLOSE_DATE, CAST(CLOSED_ON AS DATE)) type dance
                // is gone. Only CLOSED tickets count: a closure is the ticket being closed (PTR
                // included, by override), on the date its clock stopped -- the same population
                // and the same rule as the Closed tile.
                + "SELECT STOP_AT AS CLOSED_AT, IS_CLOSED FROM "
                + VIEW + predicate.sql() + ") c WHERE c.IS_CLOSED = 1 AND c.CLOSED_AT IS NOT NULL"
                + ") GROUP BY PERIOD ORDER BY PERIOD";

        List<TrendResponse> periods = new ArrayList<>();
        long carried = 0L;
        for (Map<String, Object> row : jdbc.queryForList(sql, params.toArray())) {
            long created = count(row.get("CREATED"));
            long closed = count(row.get("CLOSED"));
            // Cumulative, not per-period: the queue carries over.
            carried += created - closed;
            periods.add(new TrendResponse((String) row.get("PERIOD"), created, closed,
                    carried));
        }
        log.debug("metrics trend: {} periods at granularity {}", periods.size(), format);
        return List.copyOf(periods);
    }

    private static String format(String granularity) {
        String requested = granularity == null || granularity.isBlank()
                ? DEFAULT_GRANULARITY
                : granularity.trim().toLowerCase(Locale.ROOT);
        String format = GRANULARITIES.get(requested);
        if (format == null) {
            throw new IllegalArgumentException("Unsupported granularity.");
        }
        return format;
    }

    // -------------------------------------------------------------------- top categories

    /**
     * The busiest categories, each against its own count in the preceding equal-length window.
     *
     * <p>The previous counts are fetched UNRANKED and joined in memory: a category that is
     * top-10 now but was not top-10 then must still show its real delta, and re-ranking the
     * previous window would score it against whatever happened to sit in the tenth slot.
     */
    public List<TrendResponse.TopCategory> topCategories(IncidentFilter filter, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_CATEGORY_LIMIT);
        Map<String, Long> current = categoryCounts(filter, bounded);

        IncidentFilter previous = previousWindow(filter);
        Map<String, Long> baseline = previous == null
                ? Map.of()
                : categoryCounts(previous, MAX_CATEGORY_LIMIT * 100);

        List<TrendResponse.TopCategory> rows = new ArrayList<>();
        current.forEach((category, count) -> {
            Long before = baseline.get(category);
            // null, not 0: "no baseline" and "unchanged" are different facts.
            Double delta = previous == null || before == null || before == 0L
                    ? null
                    : round2((count - before) * 100.0 / before);
            rows.add(new TrendResponse.TopCategory(category, count, delta));
        });
        return List.copyOf(rows);
    }

    private Map<String, Long> categoryCounts(IncidentFilter filter, int limit) {
        Predicate predicate = incidents.basePredicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(limit);

        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT " + CATEGORY_CELL + " AS CATEGORY, COUNT(*) AS CATEGORY_COUNT FROM "
                        + VIEW + predicate.sql() + " GROUP BY " + CATEGORY_CELL
                        // The name is the tie-break: equal counts otherwise leave the top-N
                        // membership up to Oracle and the chart reshuffles between refreshes.
                        + " ORDER BY COUNT(*) DESC, 1 ASC FETCH FIRST ? ROWS ONLY",
                rs -> {
                    counts.put(rs.getString("CATEGORY"), rs.getLong("CATEGORY_COUNT"));
                },
                params.toArray());
        return counts;
    }

    // ------------------------------------------------------------------ scope counts

    /**
     * One KPI pass per scope group, windowed by {@code dateFrom}/{@code dateTo} only. The figures
     * come from {@link #kpiFor}, the SAME statement the KPI tiles run, so a group's open count
     * here is that page's Open tile.
     */
    public ScopeCountsResponse scopeCounts(IncidentFilter dates) {
        return new ScopeCountsResponse(scopeCountList(dates));
    }

    private List<ScopeCountsResponse.ScopeCount> scopeCountList(IncidentFilter dates) {
        List<ScopeCountsResponse.ScopeCount> counts = new ArrayList<>();
        for (ScopeGroup group : ScopeGroup.values()) {
            KpiResponse kpi = kpiFor(new IncidentFilter(dates.dateFrom(), dates.dateTo(), null,
                    null, null, null, null, null, null, null, group, null, 0,
                    IncidentFilter.DEFAULT_PAGE_SIZE, null, null));
            counts.add(new ScopeCountsResponse.ScopeCount(group.name(), kpi.open(), kpi.closed(),
                    kpi.breached(), kpi.atRisk(), kpi.open() + kpi.closed()));
        }
        return List.copyOf(counts);
    }

    // --------------------------------------------------------------------------- wallboard

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private static final List<String> STAGE_LADDER = List.of("New", "Triage", "In Progress",
            "Blocked", "Resolved", "Closed", "Cancelled");

    private static final List<String> PRIORITY_LADDER = List.of("Critical", "High", "Medium",
            "Low");

    /** Incident scope, 1 January of the current year to today (Asia/Jakarta). No client input. */
    public WallboardResponse wallboard(boolean withSubjects) {
        LocalDate today = LocalDate.now(JAKARTA);
        LocalDate from = LocalDate.of(today.getYear(), 1, 1);
        IncidentFilter filter = new IncidentFilter(from, today, null, null, null, null, null,
                null, null, null, ScopeGroup.INCIDENT, null, 0, IncidentFilter.DEFAULT_PAGE_SIZE,
                null, null);
        Predicate base = incidents.basePredicate(filter);
        Object[] params = base.params().toArray();

        KpiResponse kpi = kpiFor(filter);

        // met = closed and not breached; closedInPeriod = closed. Both from the view's flags.
        long met = count(jdbc.queryForObject("SELECT COUNT(*) FROM " + VIEW
                + whereAnd(base, CLOSED + " AND SLA_BREACHED = 0"), Long.class, params));
        Long percent = kpi.closed() == 0
                ? null
                : Math.round(met * 100.0 / kpi.closed());

        Map<String, Long> stages = new LinkedHashMap<>();
        jdbc.query("SELECT STATUS_STAGE, COUNT(*) AS N FROM " + VIEW + base.sql()
                        + " GROUP BY STATUS_STAGE",
                rs -> {
                    stages.put(rs.getString(1), rs.getLong(2));
                }, params);
        List<WallboardResponse.StageCount> byStage = ordered(stages, STAGE_LADDER).entrySet()
                .stream().map(e -> new WallboardResponse.StageCount(e.getKey(), e.getValue()))
                .toList();

        Map<String, Long> priorities = new LinkedHashMap<>();
        jdbc.query("SELECT NVL(PRIORITY, 'Unknown') AS P, COUNT(*) AS N FROM " + VIEW + base.sql()
                        + " GROUP BY NVL(PRIORITY, 'Unknown')",
                rs -> {
                    priorities.merge(rs.getString(1), rs.getLong(2), Long::sum);
                }, params);
        // 'Unclassified' is the view's name for an unresolved priority; the wire says Unknown.
        Long unclassified = priorities.remove("Unclassified");
        if (unclassified != null) {
            priorities.merge("Unknown", unclassified, Long::sum);
        }
        List<WallboardResponse.PriorityCount> byPriority = ordered(priorities, PRIORITY_LADDER)
                .entrySet().stream()
                .map(e -> new WallboardResponse.PriorityCount(e.getKey(), e.getValue()))
                .toList();

        List<WallboardResponse.OpenTicket> criticalHighOpen = jdbc.query("SELECT NO_INCIDENT, "
                        + "SUBJECT, STATUS, PRIORITY, ELAPSED_DAYS, SLA_DAYS FROM " + VIEW
                        + whereAnd(base, OPEN + " AND PRIORITY IN ('Critical', 'High')")
                        + " ORDER BY NVL(SLA_RATIO, 0) DESC, NO_INCIDENT ASC"
                        + " FETCH FIRST 10 ROWS ONLY",
                (rs, n) -> new WallboardResponse.OpenTicket(rs.getLong("NO_INCIDENT"),
                        withSubjects ? rs.getString("SUBJECT") : null, rs.getString("STATUS"),
                        rs.getString("PRIORITY"), nullableLong(rs, "ELAPSED_DAYS"),
                        nullableLong(rs, "SLA_DAYS")),
                params);

        List<WallboardResponse.CategoryCount> topCategories = new ArrayList<>();
        categoryCounts(filter, 5).forEach((category, n) ->
                topCategories.add(new WallboardResponse.CategoryCount(category, n)));

        Map<String, TrendResponse> byMonth = new LinkedHashMap<>();
        trend(filter, "month").forEach(period -> byMonth.put(period.period(), period));
        List<WallboardResponse.Month> monthly = new ArrayList<>();
        for (int month = 1; month <= today.getMonthValue(); month++) {
            String key = String.format("%d-%02d", today.getYear(), month);
            TrendResponse period = byMonth.get(key);
            monthly.add(new WallboardResponse.Month(key, period == null ? 0 : period.created(),
                    period == null ? 0 : period.closed()));
        }

        Double avg = number(jdbc.queryForObject("SELECT AVG(ELAPSED_DAYS) FROM " + VIEW
                + whereAnd(base, CLOSED), Double.class, params));
        Double avgResolutionDays = avg == null ? null : Math.round(avg * 10.0) / 10.0;

        List<ScopeCountsResponse.ScopeCount> groups = scopeCountList(filter);
        log.debug("metrics wallboard: {} open, {} closed", kpi.open(), kpi.closed());
        return new WallboardResponse(Instant.now(), today.getYear(), kpi.open() + kpi.closed(),
                kpi.open(), kpi.closed(), kpi.breached(), kpi.atRisk(),
                new WallboardResponse.SlaCompliance(met, kpi.closed(), percent), byStage,
                byPriority, criticalHighOpen, List.copyOf(topCategories), List.copyOf(monthly),
                avgResolutionDays, groups);
    }

    /** Ladder entries first (in ladder order), then anything else alphabetically; zeros dropped. */
    private static Map<String, Long> ordered(Map<String, Long> counts, List<String> ladder) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String name : ladder) {
            if (counts.containsKey(name)) {
                ordered.put(name, counts.get(name));
            }
        }
        counts.entrySet().stream().filter(e -> !ladder.contains(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByKey())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    /** The base predicate's WHERE with one more fixed (unbound) condition ANDed on. */
    private static String whereAnd(Predicate base, String condition) {
        return " WHERE " + base.condition() + " AND " + condition;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // --------------------------------------------------------------------------- helpers

    /** Case-insensitive, trimmed pair key for the SPO priority lookup. */
    private static String key(String impact, String urgency) {
        return (impact == null ? "" : impact.trim().toLowerCase(Locale.ROOT)) + '|'
                + (urgency == null ? "" : urgency.trim().toLowerCase(Locale.ROOT));
    }

    private static String cell(String column) {
        return "CASE WHEN " + column + " IS NOT NULL THEN " + column
                + " WHEN " + column + "_ID IS NOT NULL THEN 'Unknown (' || " + column
                + "_ID || ')' ELSE 'Unclassified' END";
    }

    private static long count(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
