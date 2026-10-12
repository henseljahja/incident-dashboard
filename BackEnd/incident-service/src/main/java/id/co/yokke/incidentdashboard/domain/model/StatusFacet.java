package id.co.yokke.incidentdashboard.domain.model;

/**
 * The status facet a dashboard tile applies to the table ({@code ?status=OPEN|CLOSED}).
 *
 * <p>Closed means {@code V_INCIDENT.IS_CLOSED = 1}: Redmine's flag, unless
 * {@code CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE} says otherwise (PTR and PTR with permission are
 * closed by override since V9). Nothing here restates that rule; the view owns it.
 */
public enum StatusFacet {
    OPEN,
    CLOSED
}
