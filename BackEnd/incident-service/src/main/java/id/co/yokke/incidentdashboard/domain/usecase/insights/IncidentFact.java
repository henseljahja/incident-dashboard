package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.time.LocalDate;
import java.util.Locale;

/**
 * One {@code V_INCIDENT} row reduced to what the insight widgets aggregate.
 *
 * <p>The widgets are computed in Java over these rows rather than in SQL, deliberately: the
 * arithmetic (means, ranges, compliance, seasonality) is then unit-tested against hand-computed
 * fixtures instead of being taken on trust from a query nobody can run without Oracle. The
 * flags ({@code closed}, {@code breached}, {@code atRisk}) and {@code aging} are the view's own
 * columns -- never recomputed here, so a widget and the KPI row cannot disagree about "breached".
 *
 * @param closedAt the date the SLA clock stopped ({@code STOP_AT}); {@code null} while open
 * @param aging {@code AGING_NEW}: calendar days created-to-stop-clock; {@code null} when unknown
 * @param slaDays the priority's SLA budget in days
 */
public record IncidentFact(
        long noIncident,
        String subject,
        String priority,
        String status,
        String institusi,
        String source,
        String assignee,
        String category,
        LocalDate created,
        LocalDate closedAt,
        boolean closed,
        boolean breached,
        boolean atRisk,
        Integer aging,
        Integer slaDays,
        String solution,
        boolean hasRootCause,
        boolean hasWorkaround) {

    /** The priority ladder, worst first. Anything else counts as {@code other}. */
    public static final java.util.List<String> PRIORITY_LADDER =
            java.util.List.of("Critical", "High", "Medium", "Low");

    public boolean isPriority(String name) {
        return priority != null && priority.trim().toLowerCase(Locale.ROOT)
                .equals(name.toLowerCase(Locale.ROOT));
    }

    /** {@code aging / slaDays * 100}, or {@code null} when either side is unknown or zero. */
    public Double slaUsedPct() {
        if (aging == null || slaDays == null || slaDays == 0) {
            return null;
        }
        return aging * 100.0 / slaDays;
    }
}
