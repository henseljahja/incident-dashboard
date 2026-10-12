package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.query.DefinitionsUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.query.MetricsUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.req.IncidentFilterRequest;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.DefinitionsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.KpiResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.MatrixResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.MatrixResult;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.ScopeCountsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.WallboardResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.TrendResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The aggregate read surface (Task 19). GET only, like every other {@code /api/**} route —
 * Redmine remains the write side.
 *
 * <h2>Two privilege scopes, and why {@code menu()} is an array</h2>
 *
 * <p>{@code kpi}, {@code trend} and {@code top-categories} carry NO row-level data — they are
 * counts and means — so they are reachable from {@code incident.ops}, {@code incident.exec}
 * AND {@code incident.wallboard}. The unattended wallboard holds {@code incident.wallboard}
 * and nothing else; scoping the KPI route to {@code incident.ops} would 403 it into displaying
 * a stale number forever, and widening the wallboard's grants instead would hand a TV in a
 * corridor the row-level incident list with customer names in it. The array is what lets those
 * two facts coexist.
 *
 * <p>{@code incident.ops}, {@code incident.problem} and {@code incident.sr} are ONE dataset: see
 * {@link RequirePrivilege}. Grant them together until {@code scope} is checked against grants.
 *
 * <p>{@code matrix} and {@code sla-buckets} are ops surfaces: {@code incident.ops} only.
 *
 * <p>Every handler is annotated. An undecorated {@code /api/**} handler is refused fail-closed
 * by {@code PrivilegeInterceptor} — {@code AuthFlowIT} proves both halves.
 *
 * <p>{@code @Lazy} on the injection point, as on {@link IncidentController}: a
 * {@code @RestController} is eager, and resolving the use case eagerly drags in a
 * {@code JdbcTemplate} the scaffold context (no {@code DataSource}) cannot supply.
 */
@RestController
public class MetricsController {

    private final MetricsUseCase metrics;
    private final DefinitionsUseCase definitions;

    public MetricsController(@Lazy MetricsUseCase metrics,
            @Lazy DefinitionsUseCase definitions) {
        this.metrics = metrics;
        this.definitions = definitions;
    }

    /**
     * The live policy numbers the ⓘ hints quote — SLA ladder, at-risk cushions, the statuses
     * closed by override, the stop-clock precedence and the refresh cadence. Config, not incident
     * data, so it is reachable by the same three menus as the KPI row.
     */
    @GetMapping("/api/metrics/definitions")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public DefinitionsResponse definitions() {
        return definitions.definitions();
    }

    /** Open / breached / at-risk, both mean-times, the workaround debt, and the comparison. */
    @GetMapping("/api/metrics/kpi")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public KpiResponse kpi(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(required = false) List<String> tracker,
            @RequestParam(required = false) List<String> stage,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> institusi,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Boolean hasWorkaround,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sla,
            @RequestParam(required = false) Boolean matrixMissing,
            @RequestParam(required = false) String scope) {

        return metrics.kpi(filter(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround, status, sla, scope, matrixMissing));
    }

    @GetMapping("/api/metrics/trend")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public List<TrendResponse> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(required = false) List<String> tracker,
            @RequestParam(required = false) List<String> stage,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> institusi,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Boolean hasWorkaround,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sla,
            @RequestParam(required = false) Boolean matrixMissing,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String granularity) {

        return metrics.trend(filter(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround, status, sla, scope, matrixMissing), granularity);
    }

    @GetMapping("/api/metrics/top-categories")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public List<TrendResponse.TopCategory> topCategories(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(required = false) List<String> tracker,
            @RequestParam(required = false) List<String> stage,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> institusi,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Boolean hasWorkaround,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sla,
            @RequestParam(required = false) Boolean matrixMissing,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false, defaultValue = "10") int limit) {

        return metrics.topCategories(filter(dateFrom, dateTo, tracker, stage, priority,
                institusi, category, hasWorkaround, status, sla, scope, matrixMissing), limit);
    }

    /** Ops-only: the impact/urgency heatmap is a triage surface, not an exec summary. */
    @GetMapping("/api/metrics/matrix")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr"}, privilege = "view")
    public MatrixResult matrix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(required = false) List<String> tracker,
            @RequestParam(required = false) List<String> stage,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> institusi,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Boolean hasWorkaround,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sla,
            @RequestParam(required = false) String scope) {

        return metrics.matrix(filter(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround, status, sla, scope, null));
    }

    @GetMapping("/api/metrics/sla-buckets")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr"}, privilege = "view")
    public List<MatrixResponse.SlaBucket> slaBuckets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(required = false) List<String> tracker,
            @RequestParam(required = false) List<String> stage,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> institusi,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Boolean hasWorkaround,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sla,
            @RequestParam(required = false) Boolean matrixMissing,
            @RequestParam(required = false) String scope) {

        return metrics.slaBuckets(filter(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround, status, sla, scope, matrixMissing));
    }

    /**
     * Per-group counts for the three scope tiles. Honors {@code dateFrom}/{@code dateTo} ONLY:
     * the tiles are the way to move BETWEEN scopes, so a scope or tracker narrowing would zero
     * the very tiles used to navigate.
     */
    @GetMapping("/api/metrics/scope-counts")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public ScopeCountsResponse scopeCounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo) {

        return metrics.scopeCounts(filter(dateFrom, dateTo, null, null, null, null, null, null,
                null, null, null, null));
    }

    /**
     * Everything the rotating wallboard shows, in one call. No parameters: the scope is fixed to
     * Incident and the window to the current year on the SERVER, so "Incident only" is not a
     * client choice.
     */
    @GetMapping("/api/metrics/wallboard")
    @RequirePrivilege(menu = {"incident.wallboard", "incident.ops", "incident.problem",
            "incident.sr", "incident.exec"}, privilege = "view")
    public WallboardResponse wallboard(HttpServletRequest request) {
        // Ticket subjects are row-level data: only a caller who may read the incident list sees
        // them. A wallboard- or exec-only caller gets null (the rest of the row is counts-grade).
        boolean withSubjects = request.getAttribute(AuthFilter.USER_ATTRIBUTE)
                instanceof AuthenticatedUser user
                && user.hasAnyOf(new String[] {"incident.ops", "incident.problem",
                        "incident.sr"}, "view");
        return metrics.wallboard(withSubjects);
    }

    /**
     * The same filter parameters {@code /api/incidents} accepts, validated by the same record.
     * Paging and sorting are deliberately absent: an aggregate is over the whole filtered
     * population, and honouring a page bound would silently report one page's worth of it as
     * the total.
     */
    private static IncidentFilter filter(LocalDate dateFrom, LocalDate dateTo,
            List<String> tracker, List<String> stage, List<String> priority,
            List<String> institusi, List<String> category, Boolean hasWorkaround,
            String status, String sla, String scope, Boolean matrixMissing) {
        return new IncidentFilterRequest(dateFrom, dateTo, tracker, stage, priority, institusi,
                category, hasWorkaround, status, sla, 0, IncidentFilter.DEFAULT_PAGE_SIZE, null,
                null, scope, matrixMissing).toFilter();
    }
}
