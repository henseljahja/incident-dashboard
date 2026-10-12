package id.co.yokke.incidentdashboard.domain.config.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * The ONE shape every error response in this service takes, and the one place a correlation id
 * is minted.
 *
 * <p>It exists as a separate type from {@link GlobalExceptionHandler} because errors escape
 * through two different doors: {@code @ControllerAdvice} handles anything thrown inside the
 * dispatcher, but {@code AuthFilter} rejects before the dispatcher is ever reached and has to
 * write its own response. Two writers, one body shape — otherwise a 401 from the filter and a
 * 401 from the handler look like different APIs to the SPA.
 *
 * <p><b>The body never carries a stack trace, a SQL fragment, a column name or a row value.</b>
 * The data behind this service is production-derived (customer names, staff names, incident
 * descriptions); a leaked {@code ORA-} message or query text in a 500 body is a disclosure, not
 * a debugging aid. The correlation id is the debugging aid: it is logged next to the real
 * exception, so a user report maps to exactly one server log line.
 */
public final class ErrorMapping {

    private ErrorMapping() {
    }

    /**
     * The wire body. {@code message} is always a fixed, non-reflective string for 5xx; for 4xx
     * it may describe the client's own mistake, which discloses nothing the client did not
     * already send.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            int status, String error, String message, String correlationId, Instant timestamp,
            // Additive, and omitted from the wire when null, so every existing error body is
            // byte-for-byte the shape it was. Set only where a client must branch on the cause:
            // `code` is a stable machine token (e.g. invalid_filter), `field` the parameter name.
            String code, String field) {

        public ErrorBody(
                int status, String error, String message, String correlationId, Instant timestamp) {
            this(status, error, message, correlationId, timestamp, null, null);
        }
    }

    /** Generic 5xx text. Deliberately says nothing about what failed. */
    public static final String INTERNAL_MESSAGE =
            "An internal error occurred. Quote the correlation id when reporting it.";

    /**
     * Generic authentication failure. The SAME text for a wrong password, an unknown username,
     * a locked account and a deactivated one — a distinct "account disabled" reply confirms the
     * username exists and turns {@code /auth/token} into a user-enumeration oracle.
     */
    public static final String AUTH_FAILED_MESSAGE = "Invalid username or password.";

    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    public static ErrorBody body(HttpStatus status, String message, String correlationId) {
        return new ErrorBody(status.value(), status.getReasonPhrase(), message, correlationId,
                Instant.now());
    }

    /** A 4xx body that also names a machine-readable {@code code} and the offending field. */
    public static ErrorBody body(HttpStatus status, String message, String correlationId,
            String code, String field) {
        return new ErrorBody(status.value(), status.getReasonPhrase(), message, correlationId,
                Instant.now(), code, field);
    }

    /**
     * Writes the body directly to the response — the {@code AuthFilter} path, which runs before
     * any {@code @ExceptionHandler} can see the request.
     */
    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(), body(status, message, newCorrelationId()));
    }
}
