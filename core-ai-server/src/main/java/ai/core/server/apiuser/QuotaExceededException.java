package ai.core.server.apiuser;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * Thrown when a user's daily token quota is exhausted (input or output).
 * Maps to HTTP 429 with a dedicated errorCode so clients can distinguish
 * quota exhaustion from other rate-limit scenarios.
 *
 * @author core-ai
 */
@ResponseStatus(HTTPStatus.TOO_MANY_REQUESTS)
public class QuotaExceededException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 6823341389891534390L;

    public QuotaExceededException(String message) {
        super(message);
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return "QUOTA_EXCEEDED";
    }
}
