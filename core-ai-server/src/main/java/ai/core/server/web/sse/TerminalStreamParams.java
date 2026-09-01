package ai.core.server.web.sse;

import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;

/**
 * Query-param contract for the terminal SSE bridge endpoint. The SSE listen
 * framework only supports static registration paths, so the session/sandbox/
 * terminal identifiers travel as query params rather than path params.
 * {@code sessionId} reuses the {@code agent-session-id} key so
 * {@link SseAuthInterceptor}'s existing {@code /api/sessions} -&gt; {@code chat.use}
 * mapping (and any session-based extraction) keeps working unchanged.
 *
 * @author xander
 */
public record TerminalStreamParams(String sessionId, String sandboxId, String terminalId) {
    private static final String SESSION_ID_KEY = "agent-session-id";
    private static final String SANDBOX_ID_KEY = "sandbox-id";
    private static final String TERMINAL_ID_KEY = "terminal-id";

    public static TerminalStreamParams parse(Request request) {
        var params = request.queryParams();
        return new TerminalStreamParams(
                required(params.get(SESSION_ID_KEY), SESSION_ID_KEY),
                required(params.get(SANDBOX_ID_KEY), SANDBOX_ID_KEY),
                required(params.get(TERMINAL_ID_KEY), TERMINAL_ID_KEY));
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new BadRequestException(key + " is required");
        return value;
    }
}
