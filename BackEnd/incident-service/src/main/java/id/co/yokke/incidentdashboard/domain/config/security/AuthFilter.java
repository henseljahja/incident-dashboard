package id.co.yokke.incidentdashboard.domain.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.config.error.ErrorMapping;
import id.co.yokke.incidentdashboard.domain.usecase.auth.PermissionUseCase;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proves WHO the caller is. Nothing more.
 *
 * <p><b>Authentication is not authorization.</b> What the caller may do is decided server-side
 * by {@link PrivilegeInterceptor}, per route. The SPA's {@code v-can} directive hides buttons;
 * it does nothing whatsoever to a {@code curl}, so without the second layer any authenticated
 * account could {@code GET /api/incidents?size=100000} and pull the entire production incident
 * dataset regardless of role.
 *
 * <h2>What is NOT here: request-body logging</h2>
 *
 * <p>This filter is modelled on yob's {@code OncePerRequestFilter} — its SHAPE, not its
 * logging. yob's version logs the request body. Ported as-is onto {@code POST /auth/token},
 * that writes a <b>plaintext password into the application log</b>, where it is replicated to
 * every log sink, read by everyone with log access and retained long after the credential
 * would otherwise have rotated. There is therefore no body logging at all, not even masked:
 * this service has almost no POSTs, so the debugging value is nil and the exposure is total.
 * {@code AuthFlowIT.loginLogsContainNoPassword} captures the log output of a real login and
 * asserts the password is absent.
 *
 * <h2>OPTIONS is short-circuited BEFORE authentication</h2>
 *
 * <p>A cross-origin request carrying {@code Authorization} always triggers a CORS preflight,
 * and a preflight carries NO {@code Authorization} header — it cannot, by specification.
 * Authenticating it would 401 every preflight and break the entire SPA while leaving the API
 * perfectly functional to {@code curl}, which is the confusing version of this bug.
 * {@link PrivilegeInterceptor} carries the matching exemption at its own layer.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /**
     * The only paths reachable without a token.
     *
     * <p>{@code /auth/refresh-token} is here because it authenticates with the refresh COOKIE,
     * not the {@code Authorization} header — the whole point of it is that the caller's access
     * token has expired. It performs its own, stricter validation
     * ({@code validate(token, "refresh")} plus a store lookup plus a re-check of
     * {@code IS_ACTIVE}/{@code LOCKED_UNTIL}); see {@code AuthController}.
     */
    public static final List<String> NO_AUTH =
            List.of("/health", "/auth/token", "/auth/refresh-token");

    /** The one route a forced-rotation token may reach. */
    public static final String CHANGE_PASSWORD_PATH = "/auth/change-password";

    /** Request attribute holding the {@link AuthenticatedUser}. */
    public static final String USER_ATTRIBUTE = AuthenticatedUser.class.getName();

    /** MDC key, so every log line during a request names the caller without logging content. */
    public static final String MDC_USERNAME = "username";

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    private final PermissionUseCase permissionUseCase;

    private final ObjectMapper objectMapper;

    /**
     * {@code @Lazy} on {@link PermissionUseCase}: it needs a {@code JdbcTemplate}, and the
     * scaffold context excludes {@code DataSourceAutoConfiguration}. An eager injection here
     * would fail startup for a context that never intended to serve an authenticated request.
     */
    public AuthFilter(JwtService jwtService, @Lazy PermissionUseCase permissionUseCase,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.permissionUseCase = permissionUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // FIRST, before anything that could reject: see the class javadoc on preflight.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = pathOf(request);
        if (NO_AUTH.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            ErrorMapping.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
                    "A bearer token is required.");
            return;
        }

        Claims claims;
        try {
            // ALWAYS the access type. A type-blind validate would accept the 24-hour refresh
            // token here, handing the longest-lived credential the most privilege and making
            // the short access expiry decorative.
            claims = jwtService.validate(header.substring(BEARER.length()).trim(),
                    JwtService.TYP_ACCESS);
        } catch (JwtService.JwtInvalidException e) {
            ErrorMapping.write(response, objectMapper, HttpStatus.UNAUTHORIZED,
                    "The token is not valid.");
            return;
        }

        String username = claims.getSubject();
        String scope = claims.get(JwtService.CLAIM_SCOPE, String.class);

        // FORCED ROTATION, enforced here rather than only under /api/**: the gate must also
        // cover /auth/me and /auth/logout, which the interceptor's "/api/**" mapping never
        // sees. A token issued under MUST_CHANGE_PASSWORD='Y' reaches exactly one route.
        if (JwtService.SCOPE_CHANGE_PASSWORD.equals(scope)
                && !CHANGE_PASSWORD_PATH.equals(path)) {
            ErrorMapping.write(response, objectMapper, HttpStatus.FORBIDDEN,
                    "This token may only be used to change the password.");
            return;
        }

        Set<String> grants = permissionUseCase.grantsFor(username);
        request.setAttribute(USER_ATTRIBUTE, new AuthenticatedUser(username, grants, scope));

        try {
            MDC.put(MDC_USERNAME, username);
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled and MDC is a ThreadLocal: leaving the key set
            // attributes the NEXT request's log lines to this user.
            MDC.remove(MDC_USERNAME);
        }
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
