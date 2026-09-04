package ai.core.cli.hub;

import ai.core.cli.http.RemoteApiException;

import java.net.http.HttpTimeoutException;

/**
 * Stable process exit codes for {@code core-ai-cli mcp ...} so scripts and other
 * agents can branch on the outcome without parsing output.
 *
 * @author stephen
 */
public final class HubExitCodes {
    public static final int SUCCESS = 0;
    public static final int TOOL_ERROR = 1;        // tool business failure (is_error) or server 5xx / MCP connection failure
    public static final int USAGE = 2;             // usage, invalid arguments JSON, --arg missing '='
    public static final int UNAUTHENTICATED = 3;   // no credentials or HTTP 401
    public static final int FORBIDDEN = 4;         // HTTP 403
    public static final int NOT_FOUND = 5;         // HTTP 404
    public static final int TIMEOUT = 6;           // client timeout or HTTP 504

    public static int forException(Throwable error) {
        if (error instanceof RemoteApiException apiError) {
            return switch (apiError.statusCode) {
                case 401 -> UNAUTHENTICATED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                case 504 -> TIMEOUT;
                default -> TOOL_ERROR;
            };
        }
        if (isTimeout(error)) return TIMEOUT;
        return TOOL_ERROR;
    }

    public static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    private HubExitCodes() {
    }
}
