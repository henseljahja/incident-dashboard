package id.co.yokke.incidentdashboard.domain.config.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The privilege a {@code /api/**} handler requires. Every such handler must carry one:
 * {@link PrivilegeInterceptor} is fail-closed and refuses an undecorated endpoint, because an
 * undecorated endpoint is a bug, not a public one.
 *
 * <p><b>{@link #menu()} is an ARRAY, and it is satisfied by ANY listed menu.</b> This is not
 * generality for its own sake: {@code /api/metrics/{kpi,trend,top-categories}} and
 * {@code /api/freshness} are legitimately shared by the ops, exec and wallboard roles, each of
 * which holds {@code view} on its OWN menu and nothing else (see {@code V5__seed_rbac.sql}).
 * With a single-valued annotation those routes would have to name one menu — making
 * {@code incident.exec} map to zero routes, so the executive view 403s on every metric it
 * draws and the unattended wallboard TV 403s on the freshness heartbeat that renders its
 * stale banner. Two of the three v1 views, structurally unreachable by the roles that own
 * them. Widening the grants instead would be worse: {@code incident.ops:view} also unlocks
 * the row-level incident list, customer names included.
 *
 * <p><b>{@code incident.ops}, {@code incident.problem} and {@code incident.sr} are ONE dataset.</b>
 * The {@code scope} request parameter narrows what a page shows; it is NOT checked against the
 * caller's grants, so holding any of the three reads all three scopes. Grant them together (V10
 * copies the {@code incident.ops} grants) until {@code scope} is authorised per menu.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePrivilege {

    /** Menus, ANY of which satisfies the check. Never empty. */
    String[] menu();

    /**
     * One of {@code view}, {@code export}, {@code manage} — the universe fixed by the CHECK
     * constraint on {@code APP_ROLE_PRIVILEGE}. {@code export} is deliberately separate from
     * {@code view}: bulk egress of production customer and staff names is its own privilege.
     */
    String privilege() default "view";
}
