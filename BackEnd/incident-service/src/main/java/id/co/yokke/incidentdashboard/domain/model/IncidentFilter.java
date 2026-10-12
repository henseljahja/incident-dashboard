package id.co.yokke.incidentdashboard.domain.model;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one validated, normalised description of "which incidents" — shared by
 * {@code GET /api/incidents} and by the export, so the two can never disagree about what a
 * filter means.
 *
 * <h2>Two client-controlled inputs, two different treatments</h2>
 *
 * <p><b>{@code sortColumn} lands in {@code ORDER BY}</b> and cannot be a bound parameter, so
 * it is checked against {@link #SORTABLE_COLUMNS} — an enumerated allowlist — and anything
 * else throws. A silent fallback to a default column would hide a client bug and an injection
 * attempt behind identical behaviour. Same for the direction.
 *
 * <p><b>{@code size} is CLAMPED, not refused.</b> An unbounded page is a full-dataset pull
 * through the query endpoint, which bypasses the separate {@code export} privilege entirely —
 * the bound is the security control. Clamping (and reporting the size actually served) bounds
 * the egress without turning an over-eager client into an error page.
 *
 * <p>Every other filter binds as a parameter. Nothing in this record is concatenated into SQL.
 */
public record IncidentFilter(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> trackers,
        List<String> stages,
        List<String> priorities,
        List<String> institusi,
        List<String> categories,
        Boolean hasWorkaround,
        // The two tile facets (V9). Nullable: null contributes no term. They are NOT part of the
        // base population the charts and the unfaceted KPI figures describe -- see
        // IncidentQueryUseCase.basePredicate / facetPredicate.
        StatusFacet status,
        SlaFacet sla,
        // Phase 2: server-side scope (null = all trackers) and the matrix "missing data" narrowing.
        // Both live in the base predicate, so KPI, charts, table and export cannot disagree.
        ScopeGroup scope,
        Boolean matrixMissing,
        // Mock 5 list filters. All three live in the base predicate for the same reason scope does.
        String search,
        List<String> sources,
        List<String> assignees,
        int page,
        int size,
        String sortColumn,
        String sortDirection) {

    /** The bound that keeps {@code /api/incidents} from becoming an unprivileged export. */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 50;

    public static final String DEFAULT_SORT_COLUMN = "DATE_CREATE";

    public static final String DEFAULT_SORT_DIRECTION = "DESC";

    /**
     * Appended as the final sort key, always. Equal values in the requested column otherwise
     * leave the order undefined, and paging then silently repeats or drops rows.
     */
    public static final String TIE_BREAK_COLUMN = "NO_INCIDENT";

    /** The stage whose incidents are out of the default view. */
    public static final String CANCELLED_STAGE = "Cancelled";

    /**
     * The latest date a caller may filter to.
     *
     * <p>Oracle's {@code DATE} tops out at year 9999, and {@code IncidentQueryUseCase} binds the
     * upper bound as {@code dateTo + 1 day} (half-open on the next midnight). {@code 9999-12-31}
     * therefore became {@code 10000-01-01} on the way to the driver and the query died with
     * {@code ORA-17268: Year value is out of range} — a 500, from a date the caller was perfectly
     * entitled to type. Clamping to the 30th keeps the {@code +1 day} inside year 9999.
     *
     * <p>Clamped rather than rejected for the same reason {@code size} is: the request has an
     * unambiguous meaning ("everything up to the end of time"), and it is served exactly.
     */
    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 30);

    /** The earliest date a caller may filter from; {@code DATE} has no year 0 either. */
    public static final LocalDate MIN_DATE = LocalDate.of(1, 1, 1);

    /**
     * The columns a client may sort by. Enumerated, not derived: "any column of the view" would
     * expose the ETL's own bookkeeping as an ordering surface, and a derived list cannot be
     * reviewed.
     */
    public static final Set<String> SORTABLE_COLUMNS = Set.of(
            "NO_INCIDENT", "DATE_CREATE", "UPDATED_ON", "CLOSED_ON", "CLOSE_DATE",
            "RESOLUTION_DATE", "STATUS", "STATUS_STAGE", "PRIORITY", "TRACKER", "SLA_DAYS",
            "SLA_RATIO", "AGING_NEW", "AGING_LAST_STATUS", "INSTITUSI",
            "CATEGORY_INCIDENT", "IMPACT_SEVERITY", "URGENCY_SEVERITY", "ASSIGNED_TO");

    private static final Set<String> DIRECTIONS = Set.of("ASC", "DESC");

    /** Longest free-text search term honoured; anything longer is truncated. */
    public static final int MAX_SEARCH_LENGTH = 100;

    /** Pre-search shape: scope and matrix narrowing, no search / source / assignee terms. */
    public IncidentFilter(LocalDate dateFrom, LocalDate dateTo, List<String> trackers,
            List<String> stages, List<String> priorities, List<String> institusi,
            List<String> categories, Boolean hasWorkaround, StatusFacet status, SlaFacet sla,
            ScopeGroup scope, Boolean matrixMissing, int page, int size, String sortColumn,
            String sortDirection) {
        this(dateFrom, dateTo, trackers, stages, priorities, institusi, categories,
                hasWorkaround, status, sla, scope, matrixMissing, null, null, null, page, size,
                sortColumn, sortDirection);
    }

    /** Pre-scope shape: no scope, no matrix narrowing. */
    public IncidentFilter(LocalDate dateFrom, LocalDate dateTo, List<String> trackers,
            List<String> stages, List<String> priorities, List<String> institusi,
            List<String> categories, Boolean hasWorkaround, StatusFacet status, SlaFacet sla,
            int page, int size, String sortColumn, String sortDirection) {
        this(dateFrom, dateTo, trackers, stages, priorities, institusi, categories,
                hasWorkaround, status, sla, null, null, null, null, null, page, size, sortColumn,
                sortDirection);
    }

    /** Same filter, different scope; every other term unchanged. */
    public IncidentFilter withScope(ScopeGroup newScope) {
        return new IncidentFilter(dateFrom, dateTo, trackers, stages, priorities, institusi,
                categories, hasWorkaround, status, sla, newScope, matrixMissing, search, sources,
                assignees, page, size, sortColumn, sortDirection);
    }

    public IncidentFilter {
        dateFrom = clampDate(dateFrom);
        dateTo = clampDate(dateTo);
        trackers = clean(trackers);
        stages = clean(stages);
        priorities = clean(priorities);
        institusi = clean(institusi);
        categories = clean(categories);
        sources = clean(sources);
        assignees = clean(assignees);
        search = search == null || search.isBlank() ? null
                : search.trim().substring(0, Math.min(search.trim().length(), MAX_SEARCH_LENGTH));
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        sortColumn = sortColumn(sortColumn);
        sortDirection = sortDirection(sortDirection);
    }

    /**
     * Cancelled incidents are absent from the default view and reachable only when the stage
     * is asked for by name.
     */
    public boolean includesCancelledStage() {
        return stages.stream().anyMatch(CANCELLED_STAGE::equalsIgnoreCase);
    }

    /**
     * @throws IllegalArgumentException mapped to 400 by {@code GlobalExceptionHandler} — the
     *     deliberate alternative to a silent fallback
     */
    private static String sortColumn(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_SORT_COLUMN;
        }
        String normalised = requested.trim().toUpperCase(Locale.ROOT);
        if (!SORTABLE_COLUMNS.contains(normalised)) {
            throw new IllegalArgumentException("Unsupported sort column.");
        }
        return normalised;
    }

    private static String sortDirection(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_SORT_DIRECTION;
        }
        String normalised = requested.trim().toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalised)) {
            throw new IllegalArgumentException("Unsupported sort direction.");
        }
        return normalised;
    }

    /** Holds a caller-supplied date inside the range Oracle's {@code DATE} can represent. */
    private static LocalDate clampDate(LocalDate value) {
        if (value == null) {
            return null;
        }
        if (value.isAfter(MAX_DATE)) {
            return MAX_DATE;
        }
        return value.isBefore(MIN_DATE) ? MIN_DATE : value;
    }

    /** Null-safe, blank-free, immutable — so no caller has to re-check any of the three. */
    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList()));
    }
}
