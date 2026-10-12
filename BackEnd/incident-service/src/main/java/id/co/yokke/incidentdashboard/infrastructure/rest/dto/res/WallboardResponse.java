package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.time.Instant;
import java.util.List;

/** {@code GET /api/metrics/wallboard}: Incident scope, 1 Jan of the current year to today. */
public record WallboardResponse(
        Instant generatedAt,
        int year,
        long total,
        long open,
        long closed,
        long breached,
        long atRisk,
        SlaCompliance slaCompliance,
        List<StageCount> byStage,
        List<PriorityCount> byPriority,
        List<OpenTicket> criticalHighOpen,
        List<CategoryCount> topCategories,
        List<Month> monthly,
        Double avgResolutionDays,
        List<ScopeCountsResponse.ScopeCount> groupCounts) {

    /** {@code percent} is null when nothing closed in the period. */
    public record SlaCompliance(long met, long closedInPeriod, Long percent) {
    }

    public record StageCount(String stage, long count) {
    }

    public record PriorityCount(String priority, long count) {
    }

    public record OpenTicket(long noIncident, String subject, String status, String priority,
            Long elapsedDays, Long slaDays) {
    }

    public record CategoryCount(String category, long count) {
    }

    public record Month(String month, long created, long closed) {
    }
}
