package id.co.yokke.incidentdashboard.domain.config.security;

import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Server-side authorization for every {@code /api/**} route. Fail-closed.
 *
 * <p>Registered by {@link WebConfig} — an interceptor that is merely a bean is INERT, and an
 * inert authorization layer looks exactly like a working one in every test that only checks
 * the happy path.
 *
 * <h2>Fail-closed</h2>
 *
 * <p>A handler under {@code /api/**} with no {@link RequirePrivilege} is REFUSED, not allowed.
 * An undecorated endpoint is a bug — someone added a route and forgot the annotation — and the
 * open default is the one that ships a hole quietly. Refusal turns the same mistake into an
 * immediately visible 403 plus a WARN naming the handler.
 *
 * <h2>Non-HandlerMethod is exempt, and must be</h2>
 *
 * <p>A CORS preflight is dispatched to Spring's internal {@code PreFlightHandler}, which is not
 * a {@code HandlerMethod} and therefore CANNOT carry an annotation — yet
 * {@code AbstractHandlerMapping.getCorsHandlerExecutionChain} retains the matched interceptors
 * on the preflight chain. A fail-closed check that did not exempt it would throw on a handler
 * that has no way to satisfy it, 403ing every preflight and breaking the entire SPA. The same
 * exemption covers {@code ResourceHttpRequestHandler} and the default 404 handler.
 */
@Component
public class PrivilegeInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Preflight and static/error handlers. See the class javadoc: this is a
            // correctness requirement, not a convenience.
            return true;
        }

        RequirePrivilege required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequirePrivilege.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequirePrivilege.class);
        }
        if (required == null || required.menu().length == 0) {
            log.warn("Refusing {} {}: handler {}#{} declares no @RequirePrivilege.",
                    request.getMethod(), request.getRequestURI(),
                    handlerMethod.getBeanType().getSimpleName(),
                    handlerMethod.getMethod().getName());
            throw new AccessDeniedException("This endpoint is not available.");
        }

        Object attribute = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (!(attribute instanceof AuthenticatedUser user)) {
            // AuthFilter should have rejected already; reaching here means an /api/** path
            // slipped into NO_AUTH. Refuse rather than trust the gap.
            throw new AccessDeniedException("Not authenticated.");
        }

        // Belt and braces with AuthFilter's own check. The gate is one line and the cost of
        // it being enforced in only one place is a forced-rotation bypass.
        if (user.underForcedRotation()) {
            throw new AccessDeniedException(
                    "This token may only be used to change the password.");
        }

        if (!user.hasAnyOf(required.menu(), required.privilege())) {
            log.warn("Denied {} {} for '{}': requires {} on any of {}.", request.getMethod(),
                    request.getRequestURI(), user.username(), required.privilege(),
                    String.join(", ", required.menu()));
            throw new AccessDeniedException("You do not have access to this resource.");
        }
        return true;
    }
}
