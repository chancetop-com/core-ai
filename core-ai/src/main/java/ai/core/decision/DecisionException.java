package ai.core.decision;

/**
 * An upstream decision request failure. The status code and vendor error code are kept so callers can
 * pass them through (429 / 422) instead of flattening every failure into a generic error.
 *
 * @author stephen
 */
public class DecisionException extends RuntimeException {
    private static final long serialVersionUID = 5170145155315228035L;

    private final int statusCode;
    private final String code;

    public DecisionException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public DecisionException(int statusCode, String code, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }
}
