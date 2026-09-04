package ai.core.server.mcphub;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * The hub-side wait for an MCP tool call exceeded the requested timeout
 * (HTTP 504). The underlying MCP request keeps its own transport timeout.
 *
 * @author stephen
 */
@ResponseStatus(HTTPStatus.GATEWAY_TIMEOUT)
public class McpToolTimeoutException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 1L;

    public McpToolTimeoutException(String message) {
        super(message);
    }

    public McpToolTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return "MCP_TOOL_TIMEOUT";
    }
}
