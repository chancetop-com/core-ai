package ai.core.server.apitoolhub;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * The hub-side wait for a Service API operation call exceeded the requested timeout
 * (HTTP 504). The underlying HTTP client keeps its own (smaller) request timeout.
 *
 * @author stephen
 */
@ResponseStatus(HTTPStatus.GATEWAY_TIMEOUT)
public class ApiToolTimeoutException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 1L;

    public ApiToolTimeoutException(String message) {
        super(message);
    }

    public ApiToolTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return "API_TOOL_TIMEOUT";
    }
}
