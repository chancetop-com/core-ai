package ai.core.server.mcphub;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * The MCP server transport/connection failed while executing a hub tool call
 * (HTTP 502). The tool itself did not report a business error.
 *
 * @author stephen
 */
@ResponseStatus(HTTPStatus.BAD_GATEWAY)
public class McpServerUnavailableException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 1L;

    public McpServerUnavailableException(String message) {
        super(message);
    }

    public McpServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return "MCP_SERVER_UNAVAILABLE";
    }
}
