package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.usecase.query.InsightsUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.req.IncidentFilterRequest;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.InsightsResponse;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The widget endpoints behind the RD 22524 mock-ups. GET only; every one takes the same filter
 * query string as {@code /api/incidents} (bound into {@link IncidentFilterRequest}, so the same
 * validation applies) and none accepts paging or sorting.
 *
 * <p>The count-grade aggregates are reachable from the same menus as the KPI row minus the
 * wallboard; {@code top-aging} carries ticket subjects, so it is limited to the menus that may
 * read the incident list. Every handler is annotated: an undecorated {@code /api/**} handler is
 * refused fail-closed by {@code PrivilegeInterceptor}.
 */
@RestController
public class InsightsController {

    private final InsightsUseCase insights;

    public InsightsController(@Lazy InsightsUseCase insights) {
        this.insights = insights;
    }

    @GetMapping("/api/metrics/by-priority")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public List<InsightsResponse.LabelCount> byPriority(
            @ModelAttribute IncidentFilterRequest request) {
        return insights.byPriority(request.toFilter());
    }

    @GetMapping("/api/metrics/by-source")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public List<InsightsResponse.LabelCount> bySource(
            @ModelAttribute IncidentFilterRequest request) {
        return insights.bySource(request.toFilter());
    }

    @GetMapping("/api/metrics/top-institusi")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public List<InsightsResponse.LabelCount> topInstitusi(
            @ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.topInstitusi(request.toFilter(), limit);
    }

    @GetMapping("/api/metrics/top-assignees")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public List<InsightsResponse.Assignee> topAssignees(
            @ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.topAssignees(request.toFilter(), limit);
    }

    @GetMapping("/api/metrics/monthly-comparison")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public List<InsightsResponse.Month> monthlyComparison(
            @ModelAttribute IncidentFilterRequest request) {
        return insights.monthlyComparison(request.toFilter());
    }

    @GetMapping("/api/metrics/top-aging")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr"},
            privilege = "view")
    public List<InsightsResponse.AgingRow> topAging(
            @ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.topAging(request.toFilter(), limit);
    }

    @GetMapping("/api/metrics/forecast")
    @RequirePrivilege(menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec"},
            privilege = "view")
    public InsightsResponse.Forecast forecast(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "14") int horizonDays) {
        return insights.forecast(request.toFilter(), horizonDays);
    }
}
