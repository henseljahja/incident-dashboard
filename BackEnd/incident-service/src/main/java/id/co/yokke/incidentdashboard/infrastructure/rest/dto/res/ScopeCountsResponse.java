package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.util.List;

/** {@code GET /api/metrics/scope-counts}: always three entries, INCIDENT, PROBLEM, SERVICE_REQUEST. */
public record ScopeCountsResponse(List<ScopeCount> scopes) {

    /** {@code open + closed == total}, the KPI invariant, per scope. */
    public record ScopeCount(String scope, long open, long closed, long breached, long atRisk,
            long total) {
    }
}
