package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.usecase.etl.FreshnessUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.FreshnessUseCase.Freshness;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/freshness} — the one staleness reading in the system (Task 12).
 *
 * <p>Returns {@code {lastRunAt, lastSourceTs, ageSeconds, stale, staleReason}}. The ops
 * heartbeat (Task 20) and the wallboard (Task 21) both poll it, and neither computes staleness
 * for itself: see {@link FreshnessUseCase} for why a second implementation is a bug rather than
 * a convenience.
 *
 * <p><b>Annotated by Task 14</b> with all THREE menus. Scoping it to {@code incident.ops}
 * alone is the bug the route map names by example: {@code WALLBOARD} holds
 * {@code incident.wallboard:view} and nothing else, so the unattended TV would 403 on the one
 * call that tells it the numbers on screen are stale — and it would go on displaying them.
 */
@RestController
public class FreshnessController {

    private final FreshnessUseCase freshnessUseCase;

    /**
     * {@code @Lazy} at the injection point, not just on the bean definition: a
     * {@code @RestController} is eager, and resolving {@code FreshnessUseCase} eagerly drags
     * in a {@code JdbcTemplate} that the scaffold context (no {@code DataSource}) cannot
     * supply, failing startup for a context that never intended to serve this endpoint.
     */
    public FreshnessController(@Lazy FreshnessUseCase freshnessUseCase) {
        this.freshnessUseCase = freshnessUseCase;
    }

    @GetMapping("/api/freshness")
    @RequirePrivilege(
            menu = {"incident.ops", "incident.problem", "incident.sr", "incident.exec",
                    "incident.wallboard"},
            privilege = "view")
    public Freshness freshness() {
        return freshnessUseCase.freshness();
    }
}
