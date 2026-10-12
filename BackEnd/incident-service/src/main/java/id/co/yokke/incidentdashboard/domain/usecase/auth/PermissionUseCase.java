package id.co.yokke.incidentdashboard.domain.usecase.auth;

import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves what a user may do: the {@code menuId:privilege} grant set every authorization
 * decision is made against, plus the menu list and landing route the SPA renders.
 *
 * <p>ONE query path for both. The interceptor's grant set and the {@code /auth/me} payload are
 * built from the same rows on purpose — if the UI's idea of a user's privileges came from a
 * different query than the server's enforcement, the two would drift and the visible symptom
 * would be a button that 403s.
 *
 * <p>{@code @Lazy}, like every other DB-touching bean here: the scaffold context excludes
 * {@code DataSourceAutoConfiguration}, so there is no {@code JdbcTemplate} to inject until an
 * integration test supplies a real datasource.
 */
@Service
@Lazy
public class PermissionUseCase {

    /** One guarded surface and the privileges the user holds on it. */
    public record MenuPermission(String menuId, String label, String route,
            List<String> privileges) {
    }

    /**
     * The whole permission payload. {@code grants} is the enforcement view,
     * {@code menus}/{@code defaultRoute} the presentation view; both come from the same read.
     */
    public record Permissions(String username, String displayName, String defaultRoute,
            List<MenuPermission> menus, Set<String> grants) {
    }

    private static final String GRANTS_SQL = """
            SELECT p.MENU_ID, p.PRIVILEGE, m.LABEL, m.ROUTE, m.SORT_ORDER
              FROM APP_ROLE_PRIVILEGE p
              JOIN APP_USER_ROLE ur ON ur.ROLE_ID = p.ROLE_ID
              JOIN APP_MENU m ON m.MENU_ID = p.MENU_ID
             WHERE ur.USERNAME = ?
             ORDER BY m.SORT_ORDER, p.MENU_ID, p.PRIVILEGE
            """;

    /**
     * Lowest ROLE_PRIORITY wins, and {@code APP_ROLE.ROLE_PRIORITY} is UNIQUE, so a multi-role
     * user's landing route is deterministic rather than whatever the optimiser returned first.
     */
    private static final String DEFAULT_ROUTE_SQL = """
            SELECT r.DEFAULT_ROUTE
              FROM APP_ROLE r
              JOIN APP_USER_ROLE ur ON ur.ROLE_ID = r.ROLE_ID
             WHERE ur.USERNAME = ?
             ORDER BY r.ROLE_PRIORITY
             FETCH FIRST 1 ROWS ONLY
            """;

    private static final String DISPLAY_NAME_SQL =
            "SELECT DISPLAY_NAME FROM APP_USER WHERE USERNAME = ?";

    private final JdbcTemplate jdbc;

    public PermissionUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The grant set alone — what {@code AuthFilter} needs on every request. Never null: a user
     * with no roles gets an EMPTY set, which fails closed on every {@code /api/**} route rather
     * than throwing (an authenticated account with no grants is a legitimate, if useless,
     * state; see {@code R__SeedAdmin} on the admin-without-role case).
     */
    public Set<String> grantsFor(String username) {
        Set<String> grants = new LinkedHashSet<>();
        jdbc.query(GRANTS_SQL, rs -> {
            grants.add(AuthenticatedUser.grantKey(
                    rs.getString("MENU_ID"), rs.getString("PRIVILEGE")));
        }, username);
        return grants;
    }

    /** The full payload behind {@code /auth/token} and {@code /auth/me}. */
    public Permissions permissionsFor(String username) {
        Map<String, MenuPermission> menus = new LinkedHashMap<>();
        Set<String> grants = new LinkedHashSet<>();
        jdbc.query(GRANTS_SQL, rs -> {
            String menuId = rs.getString("MENU_ID");
            String privilege = rs.getString("PRIVILEGE");
            grants.add(AuthenticatedUser.grantKey(menuId, privilege));
            MenuPermission menu = menus.get(menuId);
            if (menu == null) {
                menu = new MenuPermission(menuId, trim(rs.getString("LABEL")),
                        trim(rs.getString("ROUTE")), new ArrayList<>());
                menus.put(menuId, menu);
            }
            menu.privileges().add(privilege);
        }, username);

        List<String> routes = jdbc.queryForList(DEFAULT_ROUTE_SQL, String.class, username);
        List<String> displayNames =
                jdbc.queryForList(DISPLAY_NAME_SQL, String.class, username);

        return new Permissions(
                username,
                displayNames.isEmpty() ? null : displayNames.get(0),
                routes.isEmpty() ? null : routes.get(0),
                List.copyOf(menus.values()),
                Set.copyOf(grants));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
