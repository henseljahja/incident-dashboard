package id.co.yokke.incidentdashboard.domain.usecase.query;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.model.SlaFacet;
import id.co.yokke.incidentdashboard.domain.model.StatusFacet;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.IncidentRowDto;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.PageResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The read path over {@code V_INCIDENT} (Task 16).
 *
 * <h2>Reads the view, never the fact</h2>
 *
 * <p>Everything clock-derived — aging, SLA ratio, breach flag, bucket — lives on the view and
 * is computed from ONE shared elapsed expression. Querying {@code INCIDENT_FACT} directly means
 * recomputing those in Java, which is how the mart ends up with two disagreeing definitions of
 * "breached".
 *
 * <h2>The projection is generated from the catalog</h2>
 *
 * <p>{@link #columns()} reads {@code USER_TAB_COLUMNS} once, exactly as
 * {@code MartRefreshRepository.buildMerge()} does, minus
 * {@link IncidentRowDto#INTERNAL_COLUMNS}. Nothing here enumerates the report's fields, so
 * {@code V3__mart.sql} stays the single source of truth for them. It is also the only
 * identifier text that ever reaches the SQL string: it comes from Oracle, not from a client.
 *
 * <h2>What is bound and what is allowlisted</h2>
 *
 * <p>Every filter value is a bound parameter. The two inputs that CANNOT be bound — the sort
 * column and the direction — are validated against enumerated allowlists in
 * {@link IncidentFilter} and throw rather than fall back.
 *
 * <p><b>Nothing here logs a row.</b> This data is production-derived customer and staff names;
 * counts and durations are the only things that go to the log, and bulk egress is recorded in
 * {@code EXPORT_AUDIT} instead (see {@code .agents/patterns/logging.md}).
 */
@Service
@Lazy
public class IncidentQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncidentQueryUseCase.class);

    /** The ONLY read surface. Never {@code INCIDENT_FACT}. */
    private static final String VIEW = "V_INCIDENT";

    /**
     * A row the impact/urgency matrix cannot place on a real axis: either dimension null or
     * blank (Oracle stores '' as NULL, so {@code TRIM(x) IS NULL} covers both), the literal
     * {@code 'Unclassified'} the mart writes for an absent value, or {@code 'Unknown (<id>)'}
     * for an enum id with no label. ONE fragment, used by the filter and by the matrix's
     * {@code missingCount}, so "N missing" opens exactly N.
     */
    public static final String MATRIX_MISSING = "("
            + missing("IMPACT_SEVERITY") + " OR " + missing("URGENCY_SEVERITY") + ")";

    private static String missing(String column) {
        return "(TRIM(" + column + ") IS NULL OR " + column + " = 'Unclassified' OR "
                + column + " LIKE 'Unknown (%')";
    }

    private final JdbcTemplate jdbc;

    /** Built once from the catalog, on first use. */
    private volatile List<String> columns;

    public IncidentQueryUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The view's columns, in catalog order, minus the ETL's own bookkeeping.
     *
     * <p>Public so the integration test can assert the DTO/view correspondence against the
     * same list the projection is built from — a second, hand-typed list would be the drift
     * the assertion exists to prevent.
     */
    public List<String> columns() {
        List<String> cached = columns;
        if (cached == null) {
            synchronized (this) {
                cached = columns;
                if (cached == null) {
                    cached = readCatalog();
                    columns = cached;
                }
            }
        }
        return cached;
    }

    /** One page of incidents, plus the total the page was taken from. */
    public PageResponse<IncidentRowDto> page(IncidentFilter filter) {
        Predicate predicate = predicate(filter);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + VIEW + predicate.sql(),
                Long.class, predicate.params().toArray());
        long totalElements = total == null ? 0L : total;

        List<IncidentRowDto> content = List.of();
        if (totalElements > 0) {
            List<Object> params = new ArrayList<>(predicate.params());
            params.add((long) filter.page() * filter.size());
            params.add(filter.size());
            content = jdbc.query("SELECT " + projection() + " FROM " + VIEW + predicate.sql()
                            + orderBy(filter) + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                    (rs, rowNum) -> IncidentRowDto.from(rs, columns()), params.toArray());
        }
        // Counts and durations only -- never a row.
        log.debug("incident query: {} of {} rows, page {} size {}", content.size(),
                totalElements, filter.page(), filter.size());
        return PageResponse.of(content, filter.page(), filter.size(), totalElements);
    }

    /**
     * The export's re-query: the FULL filtered set, server-side, up to {@code limit} rows.
     *
     * <p>Deliberately NOT reachable from a controller. {@code IncidentExportUseCase} is the
     * only caller, because it is the only place that also writes the audit row — see its
     * javadoc.
     */
    List<IncidentRowDto> rows(IncidentFilter filter, int limit) {
        Predicate predicate = predicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(Math.max(limit, 0));
        return jdbc.query("SELECT " + projection() + " FROM " + VIEW + predicate.sql()
                        + orderBy(filter) + " FETCH FIRST ? ROWS ONLY",
                (rs, rowNum) -> IncidentRowDto.from(rs, columns()), params.toArray());
    }

    // ------------------------------------------------------------------ SQL construction

    /**
     * A WHERE clause and the values it binds. Never any client text in the SQL half.
     *
     * <p>{@code sql} is either empty or {@code " WHERE <condition>"}, so it can be appended
     * straight after {@code FROM V_INCIDENT}.
     */
    public record Predicate(String sql, List<Object> params) {

        private static final String WHERE = " WHERE ";

        /** The bare boolean condition, for use inside an expression; {@code 1 = 1} when empty. */
        public String condition() {
            return sql.isEmpty() ? "1 = 1" : sql.substring(WHERE.length());
        }

        /** Both predicates must hold. Params keep their order: this one's, then {@code other}'s. */
        public Predicate and(Predicate other) {
            List<String> terms = new ArrayList<>(2);
            if (!sql.isEmpty()) {
                terms.add(condition());
            }
            if (!other.sql().isEmpty()) {
                terms.add(other.condition());
            }
            List<Object> joined = new ArrayList<>(params);
            joined.addAll(other.params());
            return of(terms, joined);
        }

        static Predicate of(List<String> terms, List<Object> params) {
            return new Predicate(terms.isEmpty() ? "" : WHERE + String.join(" AND ", terms),
                    List.copyOf(params));
        }
    }

    /**
     * THE filter predicate — base AND facets — the one used by the table and the export. The
     * aggregates in {@code MetricsUseCase} (Task 19) use its two halves,
     * {@link #basePredicate} and {@link #facetPredicate}, so a tile and the table can only
     * disagree if these fragments do, and they are the same fragments.
     *
     * <p>Public for exactly that reason. If the metrics side owned a second copy, a filter
     * change (a new term, a different cancelled-work rule, a different half-open date bound)
     * would land on one side only: the KPI card and the table underneath it would then report
     * different totals for the same filter, and neither number would be trustworthy. The
     * matrix's cell-total equality is asserted against a {@code COUNT(*)} built from this same
     * method for the same reason.
     */
    public Predicate predicate(IncidentFilter filter) {
        return basePredicate(filter).and(facetPredicate(filter.status(), filter.sla()));
    }

    /**
     * The population every tile and chart describes: dates, tracker, stage, priority,
     * institusi, category, {@code hasWorkaround} and the cancelled-work rule — everything EXCEPT
     * the two tile facets.
     *
     * <p>Separate from {@link #facetPredicate} because the KPI row is faceted per tile, not as a
     * whole: clicking Breached must not zero the At Risk tile. {@code MetricsUseCase} therefore
     * needs the base population and the facet terms as distinct pieces; see its {@code kpiFor}.
     */
    public Predicate basePredicate(IncidentFilter filter) {
        List<String> terms = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (filter.dateFrom() != null) {
            terms.add("DATE_CREATE >= ?");
            params.add(Timestamp.valueOf(filter.dateFrom().atStartOfDay()));
        }
        if (filter.dateTo() != null) {
            // Half-open on the NEXT midnight: `<= dateTo` on a TIMESTAMP silently drops every
            // incident raised during the last day of the window.
            terms.add("DATE_CREATE < ?");
            params.add(Timestamp.valueOf(filter.dateTo().plusDays(1).atStartOfDay()));
        }
        inList(terms, params, "TRACKER", filter.trackers());
        inList(terms, params, "STATUS_STAGE", filter.stages());
        inList(terms, params, "PRIORITY", filter.priorities());
        inList(terms, params, "INSTITUSI", filter.institusi());
        inList(terms, params, "CATEGORY_INCIDENT", filter.categories());

        inList(terms, params, "EXTERNAL_INTERNAL", filter.sources());
        inList(terms, params, "ASSIGNED_TO", filter.assignees());
        inList(terms, params, "STATUS", filter.statusNames());
        if (filter.search() != null) {
            // Bound LIKE over the ticket number, subject, assignee and institusi. The term is
            // escaped so a client-supplied % or _ matches itself instead of widening the scan.
            String term = "%" + filter.search().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            terms.add("(TO_CHAR(NO_INCIDENT) LIKE ? ESCAPE '\\' OR LOWER(SUBJECT) LIKE ? "
                    + "ESCAPE '\\' OR LOWER(ASSIGNED_TO) LIKE ? ESCAPE '\\' "
                    + "OR LOWER(INSTITUSI) LIKE ? ESCAPE '\\')");
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }
        if (filter.scope() != null) {
            // Config-resolved by tracker ID (V_INCIDENT carries f.TRACKER_ID alongside the
            // TRACKER name, so a Redmine rename cannot move a ticket between groups).
            // The group token is bound.
            terms.add("TRACKER_ID IN (SELECT TRACKER_ID FROM CFG_TRACKER_SCOPE "
                    + "WHERE IS_ACTIVE = 1 AND SCOPE_GROUP = ?)");
            params.add(filter.scope().name());
        }
        if (Boolean.TRUE.equals(filter.matrixMissing())) {
            terms.add(MATRIX_MISSING);
        }
        if (!filter.includesCancelledStage()) {
            // Cancelled work is not backlog. It stays reachable by naming the stage.
            terms.add("EXCLUDE_FROM_BACKLOG = 0");
        }
        if (filter.hasWorkaround() != null) {
            // ORACLE: `WORKAROUND = ''` IS `WORKAROUND = NULL` and is NEVER true, for a real
            // NULL and for a zero-length locator alike. The MySQL source's
            // `x IS NULL OR x = ''` ported literally evaluates to false on every row --
            // artifacts/oracle-empty-clob-finding.md.
            terms.add(filter.hasWorkaround()
                    ? "DBMS_LOB.GETLENGTH(WORKAROUND) > 0"
                    : "(WORKAROUND IS NULL OR DBMS_LOB.GETLENGTH(WORKAROUND) = 0)");
        }

        return Predicate.of(terms, params);
    }

    /**
     * The tile facets, and nothing else. A {@code null} facet contributes no term. Binds nothing:
     * both facets are enum constants mapped to fixed column comparisons on view columns, so no
     * client text reaches the SQL.
     */
    public Predicate facetPredicate(StatusFacet status, SlaFacet sla) {
        List<String> terms = new ArrayList<>(2);
        if (status != null) {
            terms.add(switch (status) {
                case OPEN -> "IS_CLOSED = 0";
                case CLOSED -> "IS_CLOSED = 1";
            });
        }
        if (sla != null) {
            terms.add(switch (sla) {
                case BREACHED -> "SLA_BREACHED = 1";
                case AT_RISK -> "SLA_AT_RISK = 1";
            });
        }
        return Predicate.of(terms, List.of());
    }

    private static void inList(List<String> terms, List<Object> params, String column,
            List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        terms.add(column + " IN ("
                + values.stream().map(value -> "?").collect(Collectors.joining(", ")) + ")");
        params.addAll(values);
    }

    /**
     * {@code NO_INCIDENT} is appended as the final key, always. Equal values in the requested
     * column otherwise leave the order undefined, and Oracle is free to return a different
     * order per page — so page 2 repeats a row page 1 already showed and some row is never
     * seen at all.
     */
    private static String orderBy(IncidentFilter filter) {
        String order = " ORDER BY " + filter.sortColumn() + " " + filter.sortDirection();
        if (!IncidentFilter.TIE_BREAK_COLUMN.equals(filter.sortColumn())) {
            order += ", " + IncidentFilter.TIE_BREAK_COLUMN + " ASC";
        }
        return order;
    }

    private String projection() {
        return String.join(", ", columns());
    }

    private List<String> readCatalog() {
        List<String> catalog = new ArrayList<>();
        jdbc.query("SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME = ? "
                        + "ORDER BY COLUMN_ID",
                ps -> ps.setString(1, VIEW),
                rs -> {
                    String column = rs.getString(1).toUpperCase(Locale.ROOT);
                    if (!IncidentRowDto.INTERNAL_COLUMNS.contains(column)) {
                        catalog.add(column);
                    }
                });
        if (catalog.isEmpty()) {
            throw new IllegalStateException(
                    VIEW + " has no columns in USER_TAB_COLUMNS -- has V3__mart.sql run?");
        }
        log.debug("incident projection generated over {} catalog columns of {}", catalog.size(),
                VIEW);
        return List.copyOf(catalog);
    }
}
