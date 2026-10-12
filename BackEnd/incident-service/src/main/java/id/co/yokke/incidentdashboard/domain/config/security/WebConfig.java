package id.co.yokke.incidentdashboard.domain.config.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the two things that are invisible when they are missing: the authorization interceptor
 * and the CORS allowlist.
 *
 * <h2>The interceptor MUST be registered here</h2>
 *
 * <p>{@link PrivilegeInterceptor} being a {@code @Component} does NOT put it in the request
 * path. Without {@link #addInterceptors} it is a bean nobody calls — the entire authorization
 * layer silently does nothing while every annotation, test name and code review says it is
 * there. This defect has shipped before precisely because it reads as present.
 * {@code AuthFlowIT.theInterceptorIsActuallyRegistered} asserts the registration structurally,
 * by pulling the handler chain out of {@code RequestMappingHandlerMapping}, rather than
 * inferring it from a 403 that some other layer might also produce.
 *
 * <h2>CORS is an explicit allowlist, with credentials</h2>
 *
 * <p>The stack is cross-origin: the SPA is served from 5175, the API listens on 8085. The
 * refresh token is an {@code HttpOnly} cookie, and a browser will neither store nor send a
 * cookie on a cross-origin XHR unless {@code Access-Control-Allow-Credentials: true} is
 * returned AND the origin is echoed by name. With credentials disabled,
 * {@code /auth/refresh-token} can never succeed and sessions die at the access-token expiry.
 *
 * <p>There is no {@code *}: a wildcard origin is incompatible with credentials by
 * specification, so the browser would reject the response anyway — and an API returning a
 * wildcard for production incident data is a data-egress hole regardless.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PrivilegeInterceptor privilegeInterceptor;

    /**
     * Comma-separated origins. Configured, not hardcoded, because the deployed SPA origin is
     * not the dev one; defaulted to the dev origin so a developer checkout works unconfigured.
     */
    private final List<String> allowedOrigins;

    public WebConfig(PrivilegeInterceptor privilegeInterceptor,
            @Value("${incident.cors.allowed-origins}") List<String> allowedOrigins) {
        this.privilegeInterceptor = privilegeInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(privilegeInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                // PUT is here because user administration edits with it. The list was
                // GET/POST/OPTIONS while the whole API was read-only plus login, and that was
                // right then; adding a PUT endpoint without adding it here fails the PREFLIGHT,
                // so the browser never sends the real request and the SPA reports "Failed to
                // fetch" — a message that names neither CORS nor the method. DELETE is
                // deliberately still absent: nothing deletes, and deactivation is a PUT.
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                // Authorization is the header the whole scheme depends on; omitting it makes
                // the preflight succeed and the real request fail, which is a confusing bug.
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(1800);
    }
}
