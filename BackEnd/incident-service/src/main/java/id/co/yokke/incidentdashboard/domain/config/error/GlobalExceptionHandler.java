package id.co.yokke.incidentdashboard.domain.config.error;

import id.co.yokke.incidentdashboard.domain.config.error.ErrorMapping.ErrorBody;
import id.co.yokke.incidentdashboard.domain.config.error.exception.AccessDeniedException;
import id.co.yokke.incidentdashboard.domain.config.error.exception.InvalidFilterException;
import id.co.yokke.incidentdashboard.domain.config.security.JwtService;
import id.co.yokke.incidentdashboard.domain.usecase.auth.LoginUseCase.AuthenticationFailedException;
import id.co.yokke.incidentdashboard.domain.usecase.admin.UserAdminUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.beans.TypeMismatchException;

/**
 * ONE handler owns the whole error surface.
 *
 * <p>The mapping is fixed and load-bearing:
 *
 * <ul>
 *   <li>{@link AccessDeniedException} → <b>403</b>. Never 500: a fail-closed authorization miss
 *       surfacing as a 500 reads to operators as an outage and hides a real permission bug.
 *   <li>JWT rejections and failed logins → <b>401</b>.
 *   <li>Malformed request bodies and parameters → <b>400</b>.
 *   <li>Everything else → <b>500 carrying a correlation id and nothing else</b>.
 * </ul>
 *
 * <p><b>A 500 body never carries a stack trace, a SQL fragment, a column name or a row
 * value.</b> This service's data is production-derived; an {@code ORA-00942} message quoting a
 * table name, or a constraint violation quoting a customer name, is a disclosure to whoever
 * triggered the error. The full exception goes to the log, tagged with the same correlation id
 * the caller receives, so a user report still maps to exactly one log line.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Authenticated, not permitted — including the fail-closed "no annotation" refusal. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> accessDenied(AccessDeniedException e) {
        return respond(HttpStatus.FORBIDDEN, e.getMessage(), e);
    }

    /**
     * Covers {@code JwtExpiredException} too, since it extends {@code JwtInvalidException}.
     * The client's action differs (refresh vs. log in again) but the status does not.
     */
    @ExceptionHandler(JwtService.JwtInvalidException.class)
    public ResponseEntity<ErrorBody> invalidToken(JwtService.JwtInvalidException e) {
        return respond(HttpStatus.UNAUTHORIZED, "The token is not valid.", e);
    }

    /**
     * The generic login failure. The message is FIXED: wrong password, unknown user, locked
     * account and deactivated account must be indistinguishable to the caller, or
     * {@code /auth/token} becomes a user-enumeration oracle.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorBody> authenticationFailed(AuthenticationFailedException e) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorMapping.AUTH_FAILED_MESSAGE, e);
    }

    /**
     * Account-administration failures ({@code /api/admin/**}).
     *
     * <p>Split across two statuses on purpose. A CONFLICT is a request that was well-formed and
     * collided with existing state — the username is taken, or the administrator is trying to
     * edit their own account. Those are yob's {@code U14}/{@code U18} cases, and the screen
     * shows the message verbatim because it tells the operator something they can act on. A
     * non-conflict failure is a malformed request (blank name, unknown role) and is a 400.
     *
     * <p>Unlike {@link #authenticationFailed}, these messages are specific rather than uniform:
     * the caller already holds {@code incident.admin:manage}, so "that username exists" reveals
     * nothing they could not learn from the list they are looking at.
     */
    @ExceptionHandler(UserAdminUseCase.UserAdminException.class)
    public ResponseEntity<ErrorBody> userAdminFailed(UserAdminUseCase.UserAdminException e) {
        HttpStatus status = e.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return respond(status, e.getMessage(), e);
    }

    /** A named account that is not there. 404 rather than the 400 bucket, so the screen can tell
     * "you typed a username that does not exist" from "you left the name field blank". */
    @ExceptionHandler(UserAdminUseCase.UserNotFoundException.class)
    public ResponseEntity<ErrorBody> userNotFound(UserAdminUseCase.UserNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }

    /**
     * {@link TypeMismatchException} covers {@code MethodArgumentTypeMismatchException}, which is
     * what Spring throws when a query parameter will not convert to its declared type. It was
     * missing, so five entirely ordinary client mistakes fell through to the catch-all and came
     * back as 500s: {@code dateFrom=not-a-date}, {@code dateFrom=2026-02-31} (a real calendar,
     * a date that does not exist in it), {@code size=abc} and {@code hasWorkaround=maybe}. Each
     * one also logged at ERROR as an <i>unhandled</i> exception, so anyone watching the error log
     * could not tell a genuine fault from someone editing a URL by hand.
     *
     * <p>Note the deliberate asymmetry this leaves, which is NOT an oversight: a number that
     * parses but is out of range ({@code size=0}, {@code page=-1}) is still CLAMPED rather than
     * refused, per {@code IncidentFilter} — an unparseable value has no meaning to honour, an
     * out-of-range one does.
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingRequestValueException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            TypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception e) {
        // Safe to echo: a validation message describes the caller's own input shape, and
        // discloses nothing the caller did not already send.
        return respond(HttpStatus.BAD_REQUEST, "The request is not valid.", e);
    }

    /**
     * A filter value outside its enumerated set ({@code ?status=MAYBE}, {@code ?sla=NOPE}).
     *
     * <p>400 like every other bad parameter, but with {@code code = invalid_filter} and the
     * parameter name in {@code field}: the SPA drops unknown facets from a bookmarked URL on
     * hydrate, so this is reached by a direct API call, and a client that wants to say WHICH
     * parameter was wrong should not have to parse a sentence to do it.
     */
    @ExceptionHandler(InvalidFilterException.class)
    public ResponseEntity<ErrorBody> invalidFilter(InvalidFilterException e) {
        String correlationId = ErrorMapping.newCorrelationId();
        log.warn("{} {} [correlationId={}]", HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), correlationId, e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorMapping.body(HttpStatus.BAD_REQUEST, "The request is not valid.",
                        correlationId, InvalidFilterException.CODE, e.field()));
    }

    /**
     * Spring MVC's own protocol-level rejections — wrong method, unsupported media type, a
     * hand-thrown {@code ResponseStatusException}. Handled explicitly so the catch-all below
     * does not relabel a legitimate 405 or 415 as a 500 outage.
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            NoHandlerFoundException.class,
            ResponseStatusException.class})
    public ResponseEntity<ErrorBody> protocol(ErrorResponse e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return respond(status, status.getReasonPhrase(), (Exception) e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        String correlationId = ErrorMapping.newCorrelationId();
        // The ONLY place the real exception exists. Logged with the id the caller was given.
        log.error("Unhandled exception [correlationId={}]", correlationId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorMapping.body(HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorMapping.INTERNAL_MESSAGE, correlationId));
    }

    /**
     * Builds the 4xx body AND writes the matching log line.
     *
     * <p>Logging here is not optional decoration: this class hands the caller a correlation id
     * on EVERY error, and an id that appears in no log line is worse than no id at all — a
     * responder searches for it, finds nothing, and has learnt only that the trail is cold.
     * The 5xx path below has always logged; these did not, so a user quoting a 400 or 403 id
     * was unanswerable. {@code IllegalArgumentException} lands in the 400 bucket, so a genuine
     * server-side bug (a bad {@code Timestamp.valueOf}, a {@code NumberFormatException}) used
     * to leave no trace whatsoever.
     *
     * <p>WARN, not ERROR: a 4xx is the caller's mistake, not an outage. The exception is passed
     * so the stack frame is in the LOG — never in the response body, which carries only the
     * status, a safe message and the id.
     */
    private static ResponseEntity<ErrorBody> respond(HttpStatus status, String message,
            Exception e) {
        String correlationId = ErrorMapping.newCorrelationId();
        log.warn("{} {} [correlationId={}]", status.value(), status.getReasonPhrase(),
                correlationId, e);
        return ResponseEntity.status(status)
                .body(ErrorMapping.body(status, message, correlationId));
    }
}
