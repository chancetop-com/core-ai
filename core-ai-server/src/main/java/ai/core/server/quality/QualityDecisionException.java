package ai.core.server.quality;

/**
 * Classification of a failed decision call. The gate maps every kind to ERROR (never PASS) and exposes
 * whether the caller may retry.
 *
 * @author core-ai
 */
public class QualityDecisionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final boolean retryable;

    public QualityDecisionException(Kind kind, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }

    public enum Kind {
        PROTOCOL,
        ROUTE_DRIFT,
        PROVIDER,
        BUDGET
    }
}
