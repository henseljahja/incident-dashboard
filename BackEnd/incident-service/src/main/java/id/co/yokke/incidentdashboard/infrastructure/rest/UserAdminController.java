package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import id.co.yokke.incidentdashboard.domain.config.security.RequirePrivilege;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase.AdminUser;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase.AdminUserPage;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase.AssignableRole;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase.CreatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/admin/**} — account administration, ported from yob's {@code UserController}.
 *
 * <p>The route shapes follow yob's ({@code GET /users?search&page&size}, {@code POST /user},
 * {@code PUT /user}) rather than being redesigned, so the screens port across with their query
 * handling intact. Two differences are deliberate:
 *
 * <ul>
 *   <li><b>Mounted under {@code /api/admin} rather than at the root.</b> {@code AuthFilter}
 *       treats {@code /api/**} as the protected tree and {@code PrivilegeInterceptor} is
 *       fail-closed there; a root-mounted {@code /user} would sit outside both.</li>
 *   <li><b>No {@code CommonDataRes} envelope and no {@code @CrossOrigin("*")}.</b> This service
 *       returns bare payloads with errors shaped by {@code ErrorMapping}, and the SPA is served
 *       same-origin — a wildcard CORS header on the one write surface in the system would be a
 *       gratuitous widening.</li>
 * </ul>
 *
 * <p>yob's bulk-upload endpoints ({@code /user-bulk-validate}, {@code /user-bulk-submit}) are
 * NOT ported. They exist there to onboard member banks a spreadsheet at a time; this deployment
 * has a handful of named operators, and a bulk path that creates accounts from an unvalidated
 * spreadsheet is a large attack surface bought for no use case. Add it when someone needs it.
 *
 * <p>Every handler requires {@code incident.admin:manage}. {@code manage} governs OUR
 * {@code APP_USER*} tables and nothing else — Redmine remains the system of record for incident
 * data, and no endpoint here touches the mirror.
 */
@RestController
public class UserAdminController {

    /** Create/update payload. A record, so absent fields arrive as null rather than "". */
    public record UserUpsertRequest(String username, String name, String email, String roleId,
            Boolean active) {
    }

    private final UserAdminUseCase userAdminUseCase;

    /** {@code @Lazy} for the same reason {@code FreshnessController} needs it: a
     * {@code @RestController} is eager, and eagerly resolving a {@code JdbcTemplate}-backed
     * use case fails startup in the scaffold context, which has no {@code DataSource}. */
    public UserAdminController(@Lazy UserAdminUseCase userAdminUseCase) {
        this.userAdminUseCase = userAdminUseCase;
    }

    @GetMapping("/api/admin/users")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public AdminUserPage list(
            HttpServletRequest request,
            @RequestParam(value = "search", required = false, defaultValue = "") String search,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "10") int size) {
        return userAdminUseCase.list(currentUser(request).username(), search, page, size);
    }

    @GetMapping("/api/admin/users/{username}")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public AdminUser get(@PathVariable String username) {
        return userAdminUseCase.get(username);
    }

    @GetMapping("/api/admin/roles")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public List<AssignableRole> roles() {
        return userAdminUseCase.roles();
    }

    /**
     * Creates an account. The response carries the one-time password, which exists nowhere else
     * — the row stores only its bcrypt hash — so the screen must show it once and say so.
     */
    @PostMapping("/api/admin/users")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public CreatedUser create(HttpServletRequest request,
            @RequestBody UserUpsertRequest body) {
        return userAdminUseCase.create(currentUser(request).username(), body.username(),
                body.name(), body.email(), body.roleId());
    }

    /** {@code PUT} rather than {@code PATCH}: the screen submits the whole form, and a partial
     * update would make "cleared the email field" indistinguishable from "did not touch it". */
    @PutMapping("/api/admin/users/{username}")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public AdminUser update(HttpServletRequest request, @PathVariable String username,
            @RequestBody UserUpsertRequest body) {
        userAdminUseCase.update(currentUser(request).username(), username, body.name(),
                body.email(), body.roleId(), body.active() == null || body.active());
        return userAdminUseCase.get(username);
    }

    @PostMapping("/api/admin/users/{username}/reset-password")
    @RequirePrivilege(menu = "incident.admin", privilege = "manage")
    public CreatedUser resetPassword(HttpServletRequest request, @PathVariable String username) {
        return userAdminUseCase.resetPassword(currentUser(request).username(), username);
    }

    private static AuthenticatedUser currentUser(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (!(attribute instanceof AuthenticatedUser user)) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return user;
    }
}
