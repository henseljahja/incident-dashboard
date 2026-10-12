package id.co.yokke.incidentdashboard.infrastructure.rest.dto.req;

import id.co.yokke.incidentdashboard.domain.config.error.exception.InvalidFilterException;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.model.ScopeGroup;
import id.co.yokke.incidentdashboard.domain.model.SlaFacet;
import id.co.yokke.incidentdashboard.domain.model.StatusFacet;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The query string of {@code GET /api/incidents} and {@code /api/incidents/export}, before
 * validation.
 *
 * <p>It exists only to hand the raw parameters to {@link IncidentFilter}, which is where every
 * bound, clamp and allowlist lives. Splitting the validation between here and there is how one
 * of the two endpoints ends up with a different idea of what {@code size} or {@code sort} may
 * be — and the two must agree, because the export re-queries with the same filter the list
 * endpoint used.
 *
 * <p>{@code status} and {@code sla} arrive as raw strings and are parsed HERE rather than by
 * Spring's enum conversion, so a bad value is a 400 carrying {@code code = invalid_filter} and
 * the parameter name, instead of the generic type-mismatch 400.
 *
 * <p>Repeated parameters ({@code ?tracker=Incident&tracker=SR%20-%20Infra}) arrive as a list;
 * a single value is a one-element list.
 */
public record IncidentFilterRequest(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> tracker,
        List<String> stage,
        List<String> priority,
        List<String> institusi,
        List<String> category,
        Boolean hasWorkaround,
        String status,
        String sla,
        Integer page,
        Integer size,
        String sort,
        String direction,
        String scope,
        Boolean matrixMissing) {

    /** Pre-scope shape, kept so callers that never scope do not have to say so. */
    public IncidentFilterRequest(LocalDate dateFrom, LocalDate dateTo, List<String> tracker,
            List<String> stage, List<String> priority, List<String> institusi,
            List<String> category, Boolean hasWorkaround, String status, String sla,
            Integer page, Integer size, String sort, String direction) {
        this(dateFrom, dateTo, tracker, stage, priority, institusi, category, hasWorkaround,
                status, sla, page, size, sort, direction, null, null);
    }

    /**
     * @throws IllegalArgumentException on a non-allowlisted sort column or direction — mapped
     *     to 400, deliberately not to a silent fallback
     * @throws InvalidFilterException on a {@code status} or {@code sla} value outside its enum —
     *     mapped to 400 {@code invalid_filter}, never silently ignored: a facet that quietly
     *     does nothing makes a tile count and the table beneath it disagree
     */
    public IncidentFilter toFilter() {
        return new IncidentFilter(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround,
                facet("status", status, StatusFacet.class),
                facet("sla", sla, SlaFacet.class),
                scope(scope),
                matrixMissing,
                page == null ? 0 : page,
                size == null ? IncidentFilter.DEFAULT_PAGE_SIZE : size,
                sort, direction);
    }

    /**
     * Absent or blank means "all trackers". Case-insensitive, unlike the facets: the scope
     * token also appears in route metadata and menu config, where casing drifts. Anything else
     * is a 400 {@code invalid_filter}, never a silent "all" -- a scope that quietly widens would
     * put another group's tickets on the Problem page.
     */
    private static ScopeGroup scope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim().toUpperCase(Locale.ROOT);
        for (ScopeGroup group : ScopeGroup.values()) {
            if (group.name().equals(token)) {
                return group;
            }
        }
        throw new InvalidFilterException("scope", raw);
    }

    /**
     * Absent or empty means "no facet". Anything else must be exactly one of the contract's
     * tokens ({@code OPEN|CLOSED}, {@code BREACHED|AT_RISK}) — a single value, since a tile is a
     * single value. Deliberately strict (no case folding): the wire contract names the tokens,
     * and the SPA sends exactly those.
     */
    private static <E extends Enum<E>> E facet(String field, String raw, Class<E> type) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(raw)) {
                return constant;
            }
        }
        throw new InvalidFilterException(field, raw);
    }
}
