package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRule;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRuleUpdate;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.Param;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.SlaUpdate;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.Snapshot;
import id.co.yokke.incidentdashboard.domain.usecase.config.ParametersUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Parameters menu's API. Reading needs {@code incident.params:view}; every write needs
 * {@code incident.params:manage} (ADMIN), and records the caller in {@code CFG_CHANGE_LOG}.
 * The body carries values only -- there is no way to name a table or column.
 */
@RestController
public class ConfigController {

    private final ParametersUseCase parameters;

    public ConfigController(@Lazy ParametersUseCase parameters) {
        this.parameters = parameters;
    }

    @GetMapping("/api/config")
    @RequirePrivilege(menu = "incident.params", privilege = "view")
    public Snapshot snapshot() {
        return parameters.snapshot();
    }

    public record SlaPolicyRequest(List<SlaUpdate> items) {
    }

    public record ParamRequest(String value) {
    }

    @PutMapping("/api/config/sla-policy")
    @RequirePrivilege(menu = "incident.params", privilege = "manage")
    public Snapshot updateSlaPolicy(HttpServletRequest http, @RequestBody SlaPolicyRequest body) {
        parameters.updateSlaPolicy(username(http), body == null ? null : body.items());
        return parameters.snapshot();
    }

    @PutMapping("/api/config/alert-rules/{code}")
    @RequirePrivilege(menu = "incident.params", privilege = "manage")
    public AlertRule updateAlertRule(HttpServletRequest http, @PathVariable String code,
            @RequestBody AlertRuleUpdate body) {
        return parameters.updateAlertRule(username(http), code, body);
    }

    @PutMapping("/api/config/params/{key}")
    @RequirePrivilege(menu = "incident.params", privilege = "manage")
    public Param updateParam(HttpServletRequest http, @PathVariable String key,
            @RequestBody ParamRequest body) {
        return parameters.updateParam(username(http), key, body == null ? null : body.value());
    }

    /** The caller {@code AuthFilter} authenticated; an anonymous edit is refused, never logged. */
    private static String username(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (attribute instanceof AuthenticatedUser user) {
            return user.username();
        }
        throw new AccessDeniedException("Not authenticated.");
    }
}
