package ai.core.server.gateway;

import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewaySupportTest {
    @Test
    void clientSessionIdPrefersClaudeCodeHeader() {
        var request = requestWith("X-Claude-Code-Session-Id", "claude-session-1");
        when(request.header("session_id")).thenReturn(Optional.of("codex-session-1"));

        assertEquals("claude-session-1", GatewaySupport.clientSessionId(request));
    }

    @Test
    void clientSessionIdFallsBackToCodexHeaders() {
        assertEquals("codex-session-1", GatewaySupport.clientSessionId(requestWith("session_id", "codex-session-1")));
        assertEquals("codex-session-2", GatewaySupport.clientSessionId(requestWith("x-session-id", "codex-session-2")));
        assertEquals("codex-session-3", GatewaySupport.clientSessionId(requestWith("session-id", "codex-session-3")));
    }

    @Test
    void clientSessionIdSkipsBlankHeaders() {
        var request = requestWith("X-Claude-Code-Session-Id", " ");
        when(request.header("session_id")).thenReturn(Optional.of("codex-session-1"));

        assertEquals("codex-session-1", GatewaySupport.clientSessionId(request));
    }

    @Test
    void clientSessionIdReturnsNullWithoutSessionHeaders() {
        assertNull(GatewaySupport.clientSessionId(requestWith("X-Claude-Code-Session-Id", null)));
    }

    private Request requestWith(String header, String value) {
        var request = mock(Request.class);
        when(request.header(header)).thenReturn(Optional.ofNullable(value));
        return request;
    }
}
