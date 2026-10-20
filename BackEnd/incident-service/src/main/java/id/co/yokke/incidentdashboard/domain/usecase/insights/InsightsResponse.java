package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.util.List;

/** Wire shapes of {@code /api/insights/**}. Percentages are 0-100; a missing basis is {@code null}. */
public final class InsightsResponse {

    private InsightsResponse() {
    }

    /** Counts per priority, so every table can show the same four columns plus a remainder. */
    public record PriorityCounts(long critical, long high, long medium, long low, long other) {
        public long sum() {
            return critical + high + medium + low + other;
        }
    }

    public record LabelCount(String label, long count) {
    }

    public record DailyPoint(String date, long total, PriorityCounts byPriority) {
    }

    /**
     * The home mock-up. {@code mttr*} are over CLOSED tickets; {@code compliancePct} is
     * onTime / closed; {@code breachedOpen} is open and past SLA; {@code closurePct} is
     * closed / total; {@code avgAgingDays} is over every ticket with a known aging.
     */
    public record Home(long total, long open, long closed, Double mttrDays, Integer mttrMinDays,
            Integer mttrMaxDays, long onTime, long late, Double compliancePct, long breachedOpen,
            Double closurePct, Double avgAgingDays, PriorityCounts byPriority,
            List<DailyPoint> daily) {
    }

    public record Month(String period, long total, PriorityCounts byPriority, long closed,
            long open, Double avgResolutionDays, Double avgOpenAgeDays, long slaBreach) {
    }

    public record Assignee(int rank, String assignee, long total, long closed, long open,
            PriorityCounts byPriority, Double avgResolutionDays, Integer minResolutionDays,
            Integer maxResolutionDays, Double closureRatePct) {
    }

    public record SourceRow(String source, long total, PriorityCounts byPriority, long closed,
            long open, Double avgAgingDays) {
    }

    public record InstitusiRow(String label, long count, Double sharePct) {
    }

    public record Category(List<SourceRow> sources, List<InstitusiRow> institusi,
            List<InstitusiRow> brands) {
    }

    public record AgingRow(long noIncident, String subject, String priority, String status,
            String institusi, String assignedTo, Integer agingDays, Integer slaDays,
            Double slaUsedPct) {
    }

    // ------------------------------------------------------------------ forecast

    public record ForecastHistory(String date, long actual) {
    }

    public record ForecastDay(String date, String weekday, double predicted, double lower,
            double upper, double seasonalFactor, String risk) {
    }

    public record WeekdayFactor(String weekday, double factor) {
    }

    /**
     * @param mapePct backtest error over the last seven days; {@code null} when history is too
     *     short or every held-out day was zero (MAPE is undefined there)
     * @param accuracy GOOD (&lt;20) / FAIR (20-40) / POOR (&gt;40) / NA
     * @param recommendations codes the SPA translates: STABLE, SURGE, DROP, HIGH_RISK_DAY
     */
    public record Forecast(int horizonDays, double nextTotal, double currentTotal,
            Double changePct, double avg7, double avg14, double avg30, double trendPerDay,
            Double mapePct, String accuracy, String busiestDay, String quietestDay,
            double baseLevel, double stdDev, int historyPoints, List<ForecastHistory> history,
            List<ForecastDay> days, List<WeekdayFactor> weekdayFactors,
            List<String> recommendations) {
    }

    // ---------------------------------------------------------------------- RCA

    public record RcaRow(long noIncident, String subject, String status, String category,
            String assignedTo, Integer agingDays, boolean hasRootCause, boolean hasWorkaround,
            String solution, boolean closed) {
    }

    public record Rca(long total, long open, long withRootCause, long withWorkaround,
            long permanentSolution, long workaroundOnly, Double rootCausePct,
            Double workaroundPct, Double permanentPct, List<LabelCount> byCategory,
            List<RcaRow> missingRootCause) {
    }

    // -------------------------------------------------------------------- alerts

    public record AlertTicket(long noIncident, String subject, String priority,
            Double slaUsedPct) {
    }

    /** {@code code}: BREACHED_OPEN, AT_RISK, CRITICAL_HIGH_OPEN, UNASSIGNED_OPEN, FORECAST_SURGE. */
    public record Alert(String code, String severity, long count, List<AlertTicket> tickets) {
    }

    // ------------------------------------------------------------------------ AI

    public record DailySummary(String date, long created, long closed, long openNow,
            long breachedOpen, PriorityCounts byPriority, List<LabelCount> topInstitusi,
            List<LabelCount> topCategories, List<AlertTicket> attention, String narrative,
            String provider) {
    }

    public record Pattern(String institusi, String category, long count, String firstSeen,
            String lastSeen, long closedWithWorkaround, long permanentSolution,
            List<String> keywords, List<AlertTicket> samples, String recommendation) {
    }

    public record Patterns(List<Pattern> patterns, String narrative, String provider) {
    }

    public record SearchHit(long noIncident, String subject, String priority, String institusi,
            double relevance) {
    }

    /** {@code understood=false} means no rule matched; {@code answer} is then a hint, not data. */
    public record Answer(boolean understood, String metric, String scope, Double value,
            long matched, String unit) {
    }
}
