package id.co.yokke.incidentdashboard.domain.model;

/**
 * The SLA facet a dashboard tile applies to the table ({@code ?sla=BREACHED|AT_RISK}).
 *
 * <p>{@code BREACHED} is {@code V_INCIDENT.SLA_BREACHED = 1} (a late close stays breached);
 * {@code AT_RISK} is {@code V_INCIDENT.SLA_AT_RISK = 1} (open, not breached, days left within the
 * priority's cushion). Both are view columns, so the definitions exist exactly once.
 */
public enum SlaFacet {
    BREACHED,
    AT_RISK
}
