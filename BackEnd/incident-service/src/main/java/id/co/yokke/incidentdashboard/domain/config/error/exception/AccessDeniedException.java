package id.co.yokke.incidentdashboard.domain.config.error.exception;

/**
 * Authenticated, but not permitted. Maps to <b>403</b>.
 *
 * <p><b>This type is OURS on purpose.</b> {@code spring-boot-starter-security} is deliberately
 * off the classpath — it would install a filter chain that fights the hand-rolled
 * {@code AuthFilter} — and {@code spring-security-crypto}, the one Security artifact present,
 * ships password hashing utilities and no exception types at all. Importing
 * {@code org.springframework.security.access.AccessDeniedException} therefore does not
 * compile, and adding the starter to obtain it would break authentication to gain an
 * exception class.
 *
 * <p>It maps to 403 and never to 500: a fail-closed authorization miss surfacing as a 500
 * reads to operators as an outage and hides a real permission bug behind an alert about
 * something else.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
