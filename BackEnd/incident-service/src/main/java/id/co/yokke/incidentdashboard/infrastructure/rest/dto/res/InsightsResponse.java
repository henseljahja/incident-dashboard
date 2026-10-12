package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.util.List;

/** Row shapes for the RD 22524 dashboard widgets under {@code /api/metrics/**}. */
public final class InsightsResponse {

    private InsightsResponse() {
    }

    /** A labelled count: priority donut, external/internal donut, top institusi. */
    public record LabelCount(String label, long count) {
    }

    /**
     * One support-team member. {@code closureRatePct} is closed / total, {@code null} when the
     * assignee has no tickets in the window (never reachable today, kept honest).
     */
    public record Assignee(String assignee, long total, long open, long closed, long breached,
            Double closureRatePct) {
    }

    /** One month of the Summary &amp; Assignment comparison table. */
    public record Month(String period, long created, long closed, long breached,
            Double mttrDays) {
    }

    /** One of the longest-running OPEN tickets. {@code slaUsedPct} may exceed 100. */
    public record AgingRow(long noIncident, String subject, String priority, String assignedTo,
            String status, Long agingDays, Long slaDays, Double slaUsedPct) {
    }

    /** One day of the forecast chart; {@code actual} xor {@code forecast} is set. */
    public record ForecastPoint(String date, Long actual, Double forecast, Double lower,
            Double upper) {
    }

    /** The forecast series plus the model's own description, so the UI never overclaims. */
    public record Forecast(String method, int historyDays, int horizonDays,
            Double slopePerDay, List<ForecastPoint> points) {
    }
}
