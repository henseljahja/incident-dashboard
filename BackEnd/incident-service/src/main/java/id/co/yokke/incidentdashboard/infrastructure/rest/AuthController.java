package id.co.yokke.incidentdashboard.infrastructure.rest;

import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import id.co.yokke.incidentdashboard.domain.config.security.AuthFilter;
import id.co.yokke.incidentdashboard.domain.config.security.AuthenticatedUser;
import id.co.yokke.incidentdashboard.domain.config.security.JwtService;
import id.co.yokke.incidentdashboard.domain.config.security.SecurityProps;
import id.co.yokke.incidentdashboard.domain.usecase.auth.LoginUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.auth.LoginUseCase.LoginResult;
import id.co.yokke.incidentdashboard.domain.usecase.auth.PermissionUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.auth.PermissionUseCase.MenuPermission;
import id.co.yokke.incidentdashboard.domain.usecase.auth.PermissionUseCase.Permissions;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authentication surface: {@code /auth/token}, {@code /auth/refresh-token},
 * {@code /auth/logout}, {@code /auth/me} and {@code /auth/change-password}.
 *
 * <h2>The refresh token is NEVER in the response body</h2>
 *
 * <p>It leaves only as {@code Set-Cookie: refresh=…; HttpOnly; Secure; SameSite=Lax;
 * Path=/auth}. A token in the JSON body cannot be {@code HttpOnly}, and returning it both ways
 * makes the cheapest client implementation "stash the body copy in {@code localStorage}" —
 * which re-opens the stored-XSS-to-token-theft chain the HttpOnly cookie exists to close.
 *
 * <p>{@code SameSite=Lax}, not {@code Strict}: Strict drops the cookie on inbound navigation,
 * so following a Redmine link into the dashboard would silently log the user out.
 * {@code Path=/auth} keeps it off every {@code /api} call, where it is never needed.
 *
 * <p>{@code Secure} is on by default but configurable — see {@link SecurityProps#isCookieSecure()}.
 * A browser discards a {@code Secure} cookie from a plain-HTTP origin unless that origin is
 * {@code localhost}, so an HTTP deployment behind an IP loses the session on every reload.
 *
 * <h2>No request body is ever logged</h2>
 *
 * <p>See {@link AuthFilter}. On {@code /auth/token} a logged body is a plaintext password in
 * the application log.
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Cookie name, and the path it is scoped to. */
    static final String REFRESH_COOKIE = "refresh";

    static final String REFRESH_COOKIE_PATH = "/auth";

    /**
     * {@code POST /auth/token} request. Never logged, never echoed.
     *
     * <p><b>{@code toString()} is overridden, and that override is load-bearing.</b> A record's
     * generated {@code toString()} prints every component, and this service never logs the
     * request body itself — but Spring MVC does: at TRACE on {@code org.springframework.web},
     * {@code AbstractMessageConverterMethodArgumentResolver} logs the DESERIALISED argument
     * ("Read ... to [LoginRequest[username=…, password=…]]") and
     * {@code InvocableHandlerMethod} logs "Arguments: […]". With the default
     * {@code toString()} that is a plaintext password in the application log, written by the
     * framework, from a route that carries exactly one credential — and turning TRACE on to
     * debug a request is precisely when someone would do it. Masking here fixes both call
     * sites and every future one, which suppressing a logger name would not.
     * {@code AuthFlowIT.loginLogsContainNoPassword} captures a real login at TRACE and asserts
     * the password is absent.
     */
    public record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=***]";
        }
    }

    /** Masked for the same reason as {@link LoginRequest}: both fields are credentials. */
    public record ChangePasswordRequest(String currentPassword, String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
        }
    }

    /**
     * The permission payload the SPA's store consumes verbatim. Deliberately has NO
     * {@code refreshToken} field — see the class javadoc.
     */
    public record AuthPayload(String token, String username, String displayName,
            String defaultRoute, List<MenuPermission> menus, boolean mustChangePassword) {
    }

    private final LoginUseCase loginUseCase;

    private final PermissionUseCase permissionUseCase;

    private final JwtService jwtService;

    private final SecurityProps securityProps;

    /**
     * {@code @Lazy} on both use cases: a {@code @RestController} is eager, and these need a
     * {@code JdbcTemplate} the scaffold context (no {@code DataSource}) cannot supply.
     */
    public AuthController(@Lazy LoginUseCase loginUseCase,
            @Lazy PermissionUseCase permissionUseCase, JwtService jwtService,
            SecurityProps securityProps) {
        this.loginUseCase = loginUseCase;
        this.permissionUseCase = permissionUseCase;
        this.jwtService = jwtService;
        this.securityProps = securityProps;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<AuthPayload> token(@RequestBody LoginRequest request) {
        LoginResult result =
                loginUseCase.authenticate(request.username(), request.password());

        String jti = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(securityProps.getRefreshTtl());
        loginUseCase.storeRefreshToken(jti, result.username(), expiresAt);

        // Username only. No password, no body, no headers.
        log.info("Login succeeded for '{}' (mustChangePassword={}).", result.username(),
                result.mustChangePassword());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(jwtService.createRefreshToken(result.username(), jti),
                                securityProps.getRefreshTtl().getSeconds()).toString())
                .body(payload(result));
    }

    /**
     * Mints a new access token from the refresh COOKIE.
     *
     * <p>It is in {@code NO_AUTH} — by definition the caller's access token has expired — so
     * every check it performs is the only check. In order: the token must validate as
     * {@code typ=refresh} (never the access path, or a refresh token becomes a long-lived
     * access token); its {@code jti} must still be live in {@code AUTH_REFRESH_TOKEN}, or
     * logout and revocation do nothing; and {@code IS_ACTIVE}/{@code LOCKED_UNTIL} must be
     * re-read, or a deactivated account keeps minting access tokens for the full refresh
     * lifetime. The {@code mustChangePassword} scoping is re-derived from the CURRENT row for
     * the same reason: otherwise forced rotation is bypassed by one refresh call.
     */
    @PostMapping("/auth/refresh-token")
    public ResponseEntity<AuthPayload> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new JwtService.JwtInvalidException("No refresh token was presented.");
        }
        Claims claims = jwtService.validate(refreshToken, JwtService.TYP_REFRESH);
        String jti = claims.getId();
        if (jti == null || !loginUseCase.isRefreshTokenLive(jti)) {
            throw new JwtService.JwtInvalidException("The refresh token is not usable.");
        }
        LoginResult result = loginUseCase.reauthorize(claims.getSubject());
        return ResponseEntity.ok(payload(result));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                String jti = jwtService.validate(refreshToken, JwtService.TYP_REFRESH).getId();
                if (jti != null) {
                    loginUseCase.revokeRefreshToken(jti);
                }
            } catch (JwtService.JwtInvalidException e) {
                // Logging out with a junk cookie still clears the cookie. Failing here would
                // leave the user unable to log out of a broken session.
                log.debug("Logout presented an unusable refresh token; clearing anyway.");
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
                .build();
    }

    /** The same permission payload, minus a new token. Task 15's store re-hydrates from it. */
    @GetMapping("/auth/me")
    public AuthPayload me(HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        Permissions permissions = permissionUseCase.permissionsFor(user.username());
        return new AuthPayload(null, permissions.username(), permissions.displayName(),
                permissions.defaultRoute(), permissions.menus(), false);
    }

    /**
     * The one route reachable under {@code scope="change-password"} — see
     * {@link AuthFilter#CHANGE_PASSWORD_PATH}. Without it, forced rotation would lock the
     * bootstrap account out of the only action that clears the flag.
     */
    @PostMapping("/auth/change-password")
    public ResponseEntity<Void> changePassword(HttpServletRequest request,
            @RequestBody ChangePasswordRequest body) {
        AuthenticatedUser user = currentUser(request);
        loginUseCase.changePassword(
                user.username(), body.currentPassword(), body.newPassword());
        // The old refresh token was revoked with the rotation; drop the cookie too.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
                .build();
    }

    private AuthPayload payload(LoginResult result) {
        Permissions permissions = permissionUseCase.permissionsFor(result.username());
        String scope = result.mustChangePassword()
                ? JwtService.SCOPE_CHANGE_PASSWORD
                : JwtService.SCOPE_FULL;
        return new AuthPayload(
                jwtService.createToken(result.username(), scope),
                permissions.username(),
                permissions.displayName(),
                permissions.defaultRoute(),
                permissions.menus(),
                result.mustChangePassword());
    }

    /**
     * Instance, not static, because {@code Secure} is now configuration — see
     * {@link SecurityProps#isCookieSecure()} for why that knob exists and why it defaults on.
     * The logout paths build their expiry cookie through here too, and they must: a clearing
     * cookie whose attributes do not match the one that was set is not guaranteed to replace
     * it, which would leave a live refresh token in the browser after logout.
     */
    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(securityProps.isCookieSecure())
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static AuthenticatedUser currentUser(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (!(attribute instanceof AuthenticatedUser user)) {
            throw new AccessDeniedException("Not authenticated.");
        }
        return user;
    }
}
