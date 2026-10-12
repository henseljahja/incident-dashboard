package id.co.yokke.incidentdashboard.domain.config.security;

import java.util.Set;

/**
 * Who the caller is, and what they may do — the object {@code AuthFilter} puts in a request
 * attribute and {@code PrivilegeInterceptor} reads back.
 *
 * <p><b>{@code scope} is carried deliberately.</b> Forced rotation is a claim on the token
 * ({@code scope="change-password"}); if this object dropped it, every consumer would have to
 * re-parse the JWT to find out, and in practice none would — the rotation gate would degrade
 * into unenforceable prose. See {@link JwtService#SCOPE_CHANGE_PASSWORD}.
 *
 * <p>{@code grants} holds {@code "menuId:privilege"} strings, e.g. {@code incident.ops:export}.
 * A flat set rather than a map because every authorization question in this service is a
 * membership test, and a set makes the "any of these menus" rule of
 * {@link RequirePrivilege#menu()} a one-liner that cannot be got subtly wrong.
 */
public record AuthenticatedUser(String username, Set<String> grants, String scope) {

    /** Composes the key stored in {@link #grants}. One spelling, one place. */
    public static String grantKey(String menuId, String privilege) {
        return menuId + ':' + privilege;
    }

    /** True when the caller holds {@code privilege} on ANY of {@code menus}. */
    public boolean hasAnyOf(String[] menus, String privilege) {
        for (String menu : menus) {
            if (grants.contains(grantKey(menu, privilege))) {
                return true;
            }
        }
        return false;
    }

    /** True while the token may only be used to change the password. */
    public boolean underForcedRotation() {
        return JwtService.SCOPE_CHANGE_PASSWORD.equals(scope);
    }
}
