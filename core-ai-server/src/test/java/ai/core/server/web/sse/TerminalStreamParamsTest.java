package ai.core.server.web.sse;

import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalStreamParamsTest {
    @Test
    void parsesAllThreeParams() {
        var request = request(Map.of("agent-session-id", "s-1", "sandbox-id", "sb-1", "terminal-id", "t-1"));

        var params = TerminalStreamParams.parse(request);

        assertEquals("s-1", params.sessionId());
        assertEquals("sb-1", params.sandboxId());
        assertEquals("t-1", params.terminalId());
    }

    @Test
    void missingSessionIdIsRejected() {
        var request = request(Map.of("sandbox-id", "sb-1", "terminal-id", "t-1"));

        assertThrows(BadRequestException.class, () -> TerminalStreamParams.parse(request));
    }

    @Test
    void blankSandboxIdIsRejected() {
        var params = new HashMap<String, String>();
        params.put("agent-session-id", "s-1");
        params.put("sandbox-id", "   ");
        params.put("terminal-id", "t-1");

        assertThrows(BadRequestException.class, () -> TerminalStreamParams.parse(request(params)));
    }

    @Test
    void missingTerminalIdIsRejected() {
        var request = request(Map.of("agent-session-id", "s-1", "sandbox-id", "sb-1"));

        assertThrows(BadRequestException.class, () -> TerminalStreamParams.parse(request));
    }

    private Request request(Map<String, String> queryParams) {
        var request = mock(Request.class);
        when(request.queryParams()).thenReturn(queryParams);
        return request;
    }
}
