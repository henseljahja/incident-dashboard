package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.util.List;

/**
 * {@code GET /api/metrics/definitions} — the live policy numbers the dashboard's ⓘ hints quote.
 *
 * <p>Numbers only. The wording (meaning, formula, source) lives in the SPA's translation files;
 * the numbers come from here so they cannot drift from {@code CFG_SLA_POLICY} the first time a
 * DBA edits it. Nothing in this response is incident data.
 *
 * @param slaPolicy one entry per configured priority, ordered by {@code slaDays} ascending
 * @param unknownPriority what the ETL applies to a priority id with no policy row
 * @param closedByOverride the statuses counted as closed by {@code IS_CLOSED_OVERRIDE = 1}
 * @param closeRule the stop-clock precedence, as enum names the SPA translates
 * @param refreshIntervalMinutes the mart refresh cadence ({@code incident.etl.refresh-interval-ms})
 */
public record DefinitionsResponse(
        List<SlaPolicyItem> slaPolicy,
        UnknownPriority unknownPriority,
        List<ClosedStatus> closedByOverride,
        List<CloseRule> closeRule,
        Number refreshIntervalMinutes) {

    public DefinitionsResponse {
        slaPolicy = List.copyOf(slaPolicy);
        closedByOverride = List.copyOf(closedByOverride);
        closeRule = List.copyOf(closeRule);
    }

    public record SlaPolicyItem(long priorityId, String priorityName, int slaDays,
            int atRiskRemainingDays) {
    }

    public record UnknownPriority(int slaDays, int atRiskRemainingDays) {
    }

    public record ClosedStatus(long statusId, String statusName) {
    }

    /**
     * Where the SLA clock stops, in precedence order: the cf105 close date, then Redmine's close
     * time, then the last status change. These are the non-terminal values of
     * {@code V_INCIDENT.CLOSE_BASIS}; {@code OPEN} and {@code UNKNOWN} are states, not rules.
     */
    public enum CloseRule {
        CLOSE_DATE,
        CLOSED_ON,
        STATUS_CHANGE
    }
}
