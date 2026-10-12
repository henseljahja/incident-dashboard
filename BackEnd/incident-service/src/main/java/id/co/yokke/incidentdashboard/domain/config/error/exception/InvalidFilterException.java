package id.co.yokke.incidentdashboard.domain.config.error.exception;

/**
 * A filter query parameter carried a value outside its enumerated set — e.g.
 * {@code ?status=MAYBE} or {@code ?sla=NOPE}.
 *
 * <p>Mapped to <b>400</b> by {@code GlobalExceptionHandler}, with the machine-readable code
 * {@value #CODE} and the offending parameter NAME in the error body. The rejected value is not
 * echoed in the body; it is an enum token the caller already has, and the log line carries it.
 */
public class InvalidFilterException extends RuntimeException {

    public static final String CODE = "invalid_filter";

    private final String field;

    public InvalidFilterException(String field, String rejectedValue) {
        super("Unsupported value for filter '" + field + "': " + loggable(rejectedValue));
        this.field = field;
    }

    /**
     * The value as it may appear in a log line: bounded and restricted to token characters, so
     * a hand-edited URL cannot write arbitrary text (or a forged line break) into the log.
     */
    private static String loggable(String value) {
        if (value == null) {
            return "null";
        }
        String token = value.replaceAll("[^A-Za-z0-9_-]", "?");
        return token.length() > 32 ? token.substring(0, 32) + "..." : token;
    }

    public String field() {
        return field;
    }
}
