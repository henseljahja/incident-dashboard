package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

/**
 * One period of {@code GET /api/metrics/trend}.
 *
 * <p>{@code created} and {@code closed} are counts WITHIN the period; {@code backlog} is the
 * running carry — every {@code created} up to and including this period minus every
 * {@code closed} up to and including it. Rendering backlog as a per-period difference instead
 * of a cumulative would draw a flat line through a growing queue.
 *
 * <p>A period in which nothing was created but something was closed still appears, so a
 * drawdown is visible rather than missing.
 *
 * <h2>Why the top-category row shares this file</h2>
 *
 * <p>{@link TopCategory} is the other period-over-period shape: its {@code deltaPct} is
 * measured against the SAME derived previous window {@code KpiResponse.previousPeriod} uses —
 * equal in length to the requested one, never a calendar month. They are declared together so
 * that "the comparison window is derived from the request" is stated in one place.
 */
public record TrendResponse(String period, long created, long closed, long backlog) {

    /**
     * One row of {@code GET /api/metrics/top-categories}.
     *
     * <p>{@code deltaPct} is {@code null}, never {@code 0}, when the previous window is absent
     * or held no incident in this category: "no baseline" and "unchanged" are different facts
     * and a zero would render as the second.
     */
    public record TopCategory(String category, long count, Double deltaPct) {
    }
}
