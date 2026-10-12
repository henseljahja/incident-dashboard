package id.co.yokke.incidentdashboard.domain.usecase.query;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase.Predicate;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.InsightsResponse;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The RD 22524 widgets that are pure aggregates over {@code V_INCIDENT}: priority and
 * external/internal splits, top institusi, top assignees, the monthly comparison, the aging
 * top-N and the daily-count forecast.
 *
 * <p>Like {@link MetricsUseCase} it builds no WHERE of its own: every query starts from
 * {@link IncidentQueryUseCase#basePredicate}, so a widget and the list beneath it describe the
 * same population. Facets (status / sla tiles) are not applied -- these are period charts.
 *
 * <p>Unresolved dimensions surface as {@code Unclassified}, never as a dropped row.
 * Nothing here logs a row.
 */
@Service
@Lazy
public class InsightsUseCase {

    private static final String VIEW = "V_INCIDENT";

    /** Bounds every top-N fan-out. */
    static final int MAX_LIMIT = 50;

    /** Days of history the regression looks at, and the longest horizon it will extrapolate. */
    static final int HISTORY_DAYS = 60;

    static final int MAX_HORIZON_DAYS = 60;

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final JdbcTemplate jdbc;

    private final IncidentQueryUseCase incidents;

    public InsightsUseCase(JdbcTemplate jdbc, @Lazy IncidentQueryUseCase incidents) {
        this.jdbc = jdbc;
        this.incidents = incidents;
    }

    public List<InsightsResponse.LabelCount> byPriority(IncidentFilter filter) {
        return labelCounts(filter, "NVL(PRIORITY, 'Unclassified')", MAX_LIMIT);
    }

    public List<InsightsResponse.LabelCount> bySource(IncidentFilter filter) {
        return labelCounts(filter, "NVL(EXTERNAL_INTERNAL, 'Unclassified')", MAX_LIMIT);
    }

    public List<InsightsResponse.LabelCount> topInstitusi(IncidentFilter filter, int limit) {
        return labelCounts(filter, "NVL(INSTITUSI, 'Unclassified')", bound(limit));
    }

    private List<InsightsResponse.LabelCount> labelCounts(IncidentFilter filter, String cell,
            int limit) {
        Predicate predicate = incidents.basePredicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(limit);
        // The label is the tie-break: equal counts otherwise reshuffle between refreshes.
        return jdbc.query("SELECT " + cell + " AS LABEL, COUNT(*) AS N FROM " + VIEW
                        + predicate.sql() + " GROUP BY " + cell
                        + " ORDER BY COUNT(*) DESC, 1 ASC FETCH FIRST ? ROWS ONLY",
                (rs, i) -> new InsightsResponse.LabelCount(rs.getString("LABEL"), rs.getLong("N")),
                params.toArray());
    }

    /** Busiest assignees first. Unassigned tickets are reported as {@code Unassigned}. */
    public List<InsightsResponse.Assignee> topAssignees(IncidentFilter filter, int limit) {
        Predicate predicate = incidents.basePredicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(bound(limit));
        String cell = "NVL(ASSIGNED_TO, 'Unassigned')";
        return jdbc.query("SELECT " + cell + " AS ASSIGNEE, COUNT(*) AS TOTAL, "
                        + "SUM(CASE WHEN IS_CLOSED = 0 THEN 1 ELSE 0 END) AS OPEN_N, "
                        + "SUM(CASE WHEN IS_CLOSED = 1 THEN 1 ELSE 0 END) AS CLOSED_N, "
                        + "SUM(CASE WHEN SLA_BREACHED = 1 THEN 1 ELSE 0 END) AS BREACHED_N FROM "
                        + VIEW + predicate.sql() + " GROUP BY " + cell
                        + " ORDER BY COUNT(*) DESC, 1 ASC FETCH FIRST ? ROWS ONLY",
                (rs, i) -> {
                    long total = rs.getLong("TOTAL");
                    long closed = rs.getLong("CLOSED_N");
                    return new InsightsResponse.Assignee(rs.getString("ASSIGNEE"), total,
                            rs.getLong("OPEN_N"), closed, rs.getLong("BREACHED_N"),
                            total == 0 ? null : round2(closed * 100.0 / total));
                }, params.toArray());
    }

    /** Created / closed / breached and mean time to close per creation month. */
    public List<InsightsResponse.Month> monthlyComparison(IncidentFilter filter) {
        Predicate predicate = incidents.basePredicate(filter);
        return jdbc.query("SELECT TO_CHAR(DATE_CREATE, 'YYYY-MM') AS PERIOD, COUNT(*) AS CREATED, "
                        + "SUM(CASE WHEN IS_CLOSED = 1 THEN 1 ELSE 0 END) AS CLOSED_N, "
                        + "SUM(CASE WHEN SLA_BREACHED = 1 THEN 1 ELSE 0 END) AS BREACHED_N, "
                        + "SUM(CASE WHEN IS_CLOSED = 1 AND SLA_BREACHED = 0 THEN 1 ELSE 0 END) "
                        + "AS MET_N, "
                        + "AVG(CASE WHEN IS_CLOSED = 1 THEN AGING_NEW END) AS MTTR FROM " + VIEW
                        + predicate.sql() + " GROUP BY TO_CHAR(DATE_CREATE, 'YYYY-MM') "
                        + "ORDER BY 1",
                (rs, i) -> {
                    double mttr = rs.getDouble("MTTR");
                    boolean noMttr = rs.wasNull();
                    return new InsightsResponse.Month(rs.getString("PERIOD"),
                            rs.getLong("CREATED"), rs.getLong("CLOSED_N"),
                            rs.getLong("BREACHED_N"), rs.getLong("MET_N"),
                            noMttr ? null : round2(mttr));
                }, predicate.params().toArray());
    }

    /**
     * The longest-running OPEN tickets, with the share of SLA they have used. Row-level (it
     * carries the subject), so the controller restricts it to the incident-list menus.
     */
    public List<InsightsResponse.AgingRow> topAging(IncidentFilter filter, int limit) {
        Predicate predicate = incidents.basePredicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(bound(limit));
        return jdbc.query("SELECT NO_INCIDENT, SUBJECT, PRIORITY, ASSIGNED_TO, STATUS, "
                        + "AGING_NEW, SLA_DAYS, SLA_RATIO FROM " + VIEW
                        + predicate.sql() + (predicate.sql().isEmpty() ? " WHERE " : " AND ")
                        + "IS_CLOSED = 0 AND AGING_NEW IS NOT NULL "
                        + "ORDER BY AGING_NEW DESC, NO_INCIDENT ASC FETCH FIRST ? ROWS ONLY",
                (rs, i) -> {
                    long aging = rs.getLong("AGING_NEW");
                    long sla = rs.getLong("SLA_DAYS");
                    boolean noSla = rs.wasNull();
                    double ratio = rs.getDouble("SLA_RATIO");
                    boolean noRatio = rs.wasNull();
                    return new InsightsResponse.AgingRow(rs.getLong("NO_INCIDENT"),
                            rs.getString("SUBJECT"), rs.getString("PRIORITY"),
                            rs.getString("ASSIGNED_TO"), rs.getString("STATUS"), aging,
                            noSla ? null : sla, noRatio ? null : round2(ratio * 100.0));
                }, params.toArray());
    }

    /**
     * Daily created-ticket counts for the last {@link #HISTORY_DAYS} days (ignoring the
     * request's date window -- a forecast needs recent history, not the user's filter), then a
     * least-squares line extrapolated {@code horizonDays} ahead with a +-1.96 sigma band.
     */
    public InsightsResponse.Forecast forecast(IncidentFilter filter, int horizonDays) {
        int horizon = Math.min(Math.max(horizonDays, 1), MAX_HORIZON_DAYS);
        LocalDate today = LocalDate.now(JAKARTA);
        LocalDate from = today.minusDays(HISTORY_DAYS - 1L);
        IncidentFilter open = new IncidentFilter(from, today, filter.trackers(), filter.stages(),
                filter.priorities(), filter.institusi(), filter.categories(),
                filter.hasWorkaround(), null, null, filter.scope(), filter.matrixMissing(),
                filter.search(), filter.sources(), filter.assignees(), 0,
                IncidentFilter.DEFAULT_PAGE_SIZE, null, null);
        Predicate predicate = incidents.basePredicate(open);
        double[] counts = new double[HISTORY_DAYS];
        jdbc.query("SELECT TRUNC(DATE_CREATE) AS D, COUNT(*) AS N FROM " + VIEW + predicate.sql()
                        + " GROUP BY TRUNC(DATE_CREATE)",
                rs -> {
                    Timestamp day = rs.getTimestamp("D");
                    int index = (int) java.time.temporal.ChronoUnit.DAYS.between(from,
                            day.toLocalDateTime().toLocalDate());
                    if (index >= 0 && index < HISTORY_DAYS) {
                        counts[index] = rs.getLong("N");
                    }
                }, predicate.params().toArray());
        return buildForecast(from, counts, horizon);
    }

    /** Pure, so the arithmetic is unit-testable without a database. */
    static InsightsResponse.Forecast buildForecast(LocalDate from, double[] counts, int horizon) {
        int n = counts.length;
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (double c : counts) {
            meanY += c;
        }
        meanY /= n;
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (i - meanX) * (i - meanX);
            sxy += (i - meanX) * (counts[i] - meanY);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = meanY - slope * meanX;
        double sse = 0;
        for (int i = 0; i < n; i++) {
            double residual = counts[i] - (intercept + slope * i);
            sse += residual * residual;
        }
        double sigma = n > 2 ? Math.sqrt(sse / (n - 2)) : 0;

        List<InsightsResponse.ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new InsightsResponse.ForecastPoint(from.plusDays(i).toString(),
                    (long) counts[i], null, null, null));
        }
        for (int h = 0; h < horizon; h++) {
            double y = Math.max(0, intercept + slope * (n + h));
            points.add(new InsightsResponse.ForecastPoint(from.plusDays(n + h).toString(), null,
                    round2(y), round2(Math.max(0, y - 1.96 * sigma)), round2(y + 1.96 * sigma)));
        }
        return new InsightsResponse.Forecast("linear-regression", n, horizon, round2(slope),
                List.copyOf(points));
    }

    private static int bound(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
