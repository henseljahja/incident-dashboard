package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.time.LocalDate;

/**
 * The KPI row of {@code GET /api/metrics/kpi} (Task 19).
 *
 * <h2>Two mean-times, and the gap between them</h2>
 *
 * <p>{@code mttrWorkaroundDays} is the mean elapsed to the RESOLUTION date (the workaround
 * landed); {@code mttrPermanentDays} is the mean elapsed to the CLOSE date (the permanent fix
 * landed). Reporting only one of them is how a dashboard claims an incident is "resolved in
 * 4 days" while the permanent fix is still outstanding six weeks later.
 *
 * <p>{@code mttrGapDays} is that difference, and {@code workaroundDebt} is the population it is
 * drawn from: incidents that HAVE a resolution date and have NOT been closed. Both are DERIVED
 * on read — nothing in {@code INCIDENT_FACT} stores either — so a stale mart cannot make the
 * debt look smaller than it is.
 *
 * <h2>There is deliberately no detection-latency field here</h2>
 *
 * <p>Redmine records when a ticket was RAISED, never when the incident STARTED. Any latency
 * figure computed from this dataset would be a fabricated number on an executive dashboard,
 * so no such field exists — and {@code MetricsContractTest} asserts that it never acquires
 * one.
 *
 * <h2>The comparison window is derived, not a calendar month</h2>
 *
 * <p>{@code previousPeriod} carries the same shape over the window of the SAME LENGTH
 * immediately preceding {@code windowFrom}. A 30-day request compares against the preceding 30
 * days; a 7-day request against the preceding 7. {@code windowFrom}/{@code windowTo}/
 * {@code windowDays} are on the wire precisely so a client can verify that rather than trust
 * it. It is {@code null} when the request left either bound open — there is no defensible
 * "previous" for an unbounded window, and inventing one is worse than omitting it.
 *
 * <h2>Faceted tiles (V9, plan 2026-09-15-stakeholder-notes-p1)</h2>
 *
 * <p>The four status tiles are navigation: clicking one applies its facet to the table. Each
 * tile therefore applies the OTHER dimension's facet, never its own, so clicking Breached does
 * not zero the At Risk tile:
 *
 * <ul>
 *   <li>{@code open}     = base ∧ {@code sla} facet ∧ not closed
 *   <li>{@code closed}   = base ∧ {@code sla} facet ∧ closed
 *   <li>{@code breached} = base ∧ {@code status} facet ∧ breached
 *   <li>{@code atRisk}   = base ∧ {@code status} facet ∧ open, not breached, within the cushion
 * </ul>
 *
 * <p>With no facets, {@code open + closed == backlog}. With facets, the tile of an active facet
 * equals {@code GET /api/incidents} {@code totalElements} for the same parameters.
 *
 * <p>{@code mttrDays} follows the {@code sla} facet exactly as {@code closed} does.
 *
 * <p>{@code backlog}, {@code mttrWorkaround*}/{@code mttrPermanent*} and {@code workaroundDebt} describe the base population and
 * ignore the facets.
 */
public record KpiResponse(
        LocalDate windowFrom,
        LocalDate windowTo,
        Integer windowDays,
        // DEPRECATED: the base population, ignoring facets. The Backlog tile is gone
        // (stakeholder note 12); kept for one release for old clients, then removed. Use
        // open + closed, which equals it when no facet is applied.
        @Deprecated long backlog,
        long open,
        long closed,
        long breached,
        long atRisk,
        Double mttrWorkaroundDays,
        Double mttrPermanentDays,
        Double mttrGapDays,
        // Single MTTR (Pras, 8 Oct): mean elapsed days of CLOSED tickets -- workaround and
        // permanent solutions are one Close group. Null when nothing is closed. The three
        // mttrWorkaround/mttrPermanent/mttrGap fields and workaroundDebt are DEPRECATED, kept
        // for one release.
        Double mttrDays,
        long workaroundDebt,
        KpiResponse previousPeriod) {

    /** Same figures, with the preceding equal-length window attached. */
    @SuppressWarnings("deprecation")
    public KpiResponse withPreviousPeriod(KpiResponse previous) {
        return new KpiResponse(windowFrom, windowTo, windowDays, backlog, open, closed, breached,
                atRisk, mttrWorkaroundDays, mttrPermanentDays, mttrGapDays, mttrDays,
                workaroundDebt,
                previous);
    }
}
