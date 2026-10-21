package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.req.IncidentFilterRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The RD 22524 dashboard surface. GET only; every endpoint takes the same filter query string
 * as {@code /api/incidents} (bound into {@link IncidentFilterRequest}, so the same validation
 * applies) and none pages or sorts -- an aggregate is over the whole filtered population.
 *
 * <h2>Who may call what</h2>
 *
 * <ul>
 *   <li><b>Counts-grade</b> ({@code home}, {@code monthly}, {@code assignees}, {@code category},
 *       {@code forecast}): the dashboard and ops menus plus {@code incident.exec}.</li>
 *   <li><b>Row-level</b> ({@code aging}, {@code rca}, {@code alerts}, {@code ai/*}): they carry
 *       ticket subjects or root-cause text, so the executive and wallboard roles never reach
 *       them -- the same line {@code /api/incidents} draws.</li>
 * </ul>
 *
 * <p>Every handler is annotated: an undecorated {@code /api/**} handler is refused fail-closed
 * by {@code PrivilegeInterceptor}.
 */
@RestController
public class InsightsController {

    private final InsightsUseCase insights;

    private final boolean aiEnabled;

    /**
     * @param aiEnabled {@code incident.ai.enabled}, default false. While the AI provider and its
     *     data-handling terms are undecided, {@code /api/insights/ai/**} answers 404 so the
     *     placeholder in the SPA is backed by the server, not just by the router.
     */
    public InsightsController(@Lazy InsightsUseCase insights,
            @Value("${incident.ai.enabled:false}") boolean aiEnabled) {
        this.insights = insights;
        this.aiEnabled = aiEnabled;
    }

    private void requireAi() {
        if (!aiEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/api/insights/home")
    @RequirePrivilege(menu = {"incident.dashboard", "incident.ops", "incident.problem",
        "incident.sr", "incident.exec"}, privilege = "view")
    public InsightsResponse.Home home(@ModelAttribute IncidentFilterRequest request) {
        return insights.home(request.toFilter());
    }

    @GetMapping("/api/insights/monthly")
    @RequirePrivilege(menu = {"incident.dashboard", "incident.ops", "incident.problem",
        "incident.sr", "incident.exec"}, privilege = "view")
    public List<InsightsResponse.Month> monthly(@ModelAttribute IncidentFilterRequest request) {
        return insights.monthly(request.toFilter());
    }

    @GetMapping("/api/insights/assignees")
    @RequirePrivilege(menu = {"incident.dashboard", "incident.ops", "incident.problem",
        "incident.sr", "incident.exec"}, privilege = "view")
    public List<InsightsResponse.Assignee> assignees(
            @ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.assignees(request.toFilter(), limit);
    }

    @GetMapping("/api/insights/category")
    @RequirePrivilege(menu = {"incident.dashboard", "incident.ops", "incident.problem",
        "incident.sr", "incident.exec"}, privilege = "view")
    public InsightsResponse.Category category(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.category(request.toFilter(), limit);
    }

    @GetMapping("/api/insights/forecast")
    @RequirePrivilege(menu = {"incident.forecast", "incident.dashboard", "incident.ops",
        "incident.problem", "incident.sr", "incident.exec"}, privilege = "view")
    public InsightsResponse.Forecast forecast(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false) Integer horizonDays) {
        return insights.forecast(request.toFilter(), horizonDays);
    }

    @GetMapping("/api/insights/aging")
    @RequirePrivilege(menu = {"incident.dashboard", "incident.ops", "incident.problem",
        "incident.sr"}, privilege = "view")
    public List<InsightsResponse.AgingRow> aging(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return insights.aging(request.toFilter(), limit);
    }

    @GetMapping("/api/insights/rca")
    @RequirePrivilege(menu = {"incident.rca", "incident.problem", "incident.ops"},
            privilege = "view")
    public InsightsResponse.Rca rca(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "25") int limit) {
        return insights.rca(request.toFilter(), limit);
    }

    @GetMapping("/api/insights/alerts")
    @RequirePrivilege(menu = {"incident.alerts", "incident.ops", "incident.problem",
        "incident.sr"}, privilege = "view")
    public List<InsightsResponse.Alert> alerts(@ModelAttribute IncidentFilterRequest request) {
        return insights.alerts(request.toFilter());
    }

    @GetMapping("/api/insights/ai/summary")
    @RequirePrivilege(menu = {"incident.ai", "incident.ops"}, privilege = "view")
    public InsightsResponse.DailySummary summary(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam(required = false, defaultValue = "en") String lang) {
        requireAi();
        return insights.dailySummary(request.toFilter(),
                date == null ? LocalDate.now(ZoneId.of("Asia/Jakarta")) : date, lang);
    }

    @GetMapping("/api/insights/ai/patterns")
    @RequirePrivilege(menu = {"incident.ai", "incident.ops"}, privilege = "view")
    public InsightsResponse.Patterns patterns(@ModelAttribute IncidentFilterRequest request,
            @RequestParam(required = false, defaultValue = "en") String lang) {
        requireAi();
        return insights.patterns(request.toFilter(), lang);
    }

    @GetMapping("/api/insights/ai/search")
    @RequirePrivilege(menu = {"incident.ai", "incident.ops"}, privilege = "view")
    public List<InsightsResponse.SearchHit> search(@ModelAttribute IncidentFilterRequest request,
            @RequestParam("q") String query) {
        requireAi();
        return insights.search(request.toFilter(), query);
    }

    @GetMapping("/api/insights/ai/ask")
    @RequirePrivilege(menu = {"incident.ai", "incident.ops"}, privilege = "view")
    public InsightsResponse.Answer ask(@ModelAttribute IncidentFilterRequest request,
            @RequestParam("q") String question) {
        requireAi();
        return insights.ask(request.toFilter(), question);
    }
}
