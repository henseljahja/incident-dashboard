package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

/**
 * One cell of the impact/urgency matrix ({@code GET /api/metrics/matrix}).
 *
 * <h2>Every row of the population lands in a cell</h2>
 *
 * <p>An incident with no impact or urgency custom value is NOT dropped and is NOT given a
 * {@code null} axis a chart library will silently skip: it lands in a real cell labelled
 * {@code Unclassified}. One whose enum id has no {@code REF_CUSTOM_FIELD_ENUM} row lands in
 * {@code Unknown (<id>)} — the same convention {@code V_INCIDENT} uses for an unresolved
 * status or tracker. The sum of every cell's {@code count} therefore equals
 * {@code SELECT COUNT(*) FROM V_INCIDENT} under the SAME filter predicate that
 * {@code /api/incidents} uses, cancelled work excluded by the same rule — and
 * {@code MetricsIT} asserts exactly that equality, because a row that vanishes from a heatmap
 * under-reports the backlog to the people deciding how to staff it.
 *
 * <p>{@code priority} is the SPO priority for the (impact, urgency) pair from
 * {@code CFG_PRIORITY_MATRIX}, or null for a label outside the matrix (Unclassified, Unknown (n)).
 *
 * <h2>Why the SLA bucket shares this file</h2>
 *
 * <p>{@link SlaBucket} is the same idea over one axis instead of two: a distribution of the
 * SAME filtered population into named cells, with no cell allowed to disappear — {@code
 * V_INCIDENT} gives an unmeasurable elapsed its own {@code Unknown} band for that reason. They
 * are declared together so the "no row falls outside a cell" rule is stated once.
 */
public record MatrixResponse(String impact, String urgency, long count, String priority) {

    /** One band of {@code GET /api/metrics/sla-buckets}, over the same population. */
    public record SlaBucket(String bucket, long count) {
    }
}
