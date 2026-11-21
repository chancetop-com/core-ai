package ai.core.server.evaluation.service;

import java.io.Serial;

/**
 * @author stephen
 */
public class EvalValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public final String field;

    public EvalValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public EvalValidationException(String field, String message, Throwable cause) {
        super(message, cause);
        this.field = field;
    }
}
