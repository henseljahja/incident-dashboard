package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentExportUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentExportUseCase.ExportResult;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.req.IncidentFilterRequest;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.IncidentRowDto;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The incident read surface (Task 16). GET only — Redmine is the permanent write side and this
 * service never mutates incident data.
 *
 * <h2>Two routes, two privileges</h2>
 *
 * <p>{@code /api/incidents} needs {@code incident.ops:view}; {@code /api/incidents/export} needs
 * {@code incident.ops:export}. That split is the only privilege boundary in the system: bulk
 * egress of production customer and staff names is not the same act as reading a page of them.
 * It only holds because the export is computed SERVER-SIDE — see {@link IncidentExportUseCase}.
 *
 * <p>{@code @Lazy} at both injection points, as on {@code FreshnessController}: a
 * {@code @RestController} is eager, and resolving these use cases eagerly drags in a
 * {@code JdbcTemplate} the scaffold context (no {@code DataSource}) cannot supply.
 */
@RestController
public class IncidentController {

    private final IncidentQueryUseCase queryUseCase;
    private final IncidentExportUseCase exportUseCase;

    public IncidentController(@Lazy IncidentQueryUseCase queryUseCase,
            @Lazy IncidentExportUseCase exportUseCase) {
        this.queryUseCase = queryUseCase;
        this.exportUseCase = exportUseCase;
    }

    /**
     * One page of incidents, optionally narrowed by the tile facets {@code status}
     * ({@code OPEN|CLOSED}) and {@code sla} ({@code BREACHED|AT_RISK}). {@code size} is clamped to
     * {@link IncidentFilter#MAX_PAGE_SIZE} and the response reports the size actually served —
     * an unbounded page here would be a full-dataset pull that never touches the {@code export}
     * privilege.
     */
    @GetMapping("/api/incidents")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr"}, privilege = "view")
    public PageResponse<IncidentRowDto> incidents(
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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean matrixMissing) {

        return queryUseCase.page(new IncidentFilterRequest(dateFrom, dateTo, tracker, stage,
                priority, institusi, category, hasWorkaround, status, sla, page, size, sort,
                direction, scope, matrixMissing)
                .toFilter());
    }

    /**
     * The full filtered set — the same facets as the table included — capped and audited. The cap and the {@code EXPORT_AUDIT} row are
     * applied by the use case in one operation, so no controller change can produce rows
     * without a trail.
     */
    @GetMapping("/api/incidents/export")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr"}, privilege = "export")
    public ExportResult export(
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
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean matrixMissing,
            HttpServletRequest request) {

        // page/size are deliberately not accepted here: the export is the whole filtered set
        // up to its own cap, and honouring a page bound would make `truncated` a lie.
        IncidentFilter filter = new IncidentFilterRequest(dateFrom, dateTo, tracker, stage,
                priority, institusi, category, hasWorkaround, status, sla, 0,
                IncidentFilter.MAX_PAGE_SIZE, sort, direction, scope, matrixMissing).toFilter();
        return exportUseCase.export(username(request), filter);
    }

    /**
     * The caller {@code AuthFilter} authenticated. Refuse rather than audit an anonymous pull:
     * an {@code EXPORT_AUDIT} row with no username records that the incident happened and
     * nothing about who caused it.
     */
    private static String username(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (attribute instanceof AuthenticatedUser user) {
            return user.username();
        }
        throw new AccessDeniedException("Not authenticated.");
    }
}
