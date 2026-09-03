package ai.core.server.gateway;

import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void agentNameTrimsHeader() {
        assertEquals("menu-agent", GatewaySupport.agentName(requestWith("x-agent-name", " menu-agent ")));
        assertNull(GatewaySupport.agentName(requestWith("x-agent-name", null)));
        assertNull(GatewaySupport.agentName(requestWith("x-agent-name", "  ")));
    }

    @Test
    void parseToolCallsPairsToolCallsWithToolMessages() {
        var body = bodyOf("messages", List.of(
                Map.of("role", "user", "content", "run tools"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                        Map.of("id", "call-1", "type", "function", "function", Map.of("name", "search_menu", "arguments", "{\"q\":\"pasta\"}")),
                        Map.of("id", "call-2", "type", "function", "function", Map.of("name", "save_type", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "[\"margherita\"]"),
                Map.of("role", "tool", "tool_call_id", "call-2", "content", "saved")
        ));

        var calls = GatewaySupport.parseToolCalls(body, GatewaySupport.MAX_SYNTHESIZED_TOOL_CALLS);

        assertEquals(2, calls.size());
        assertEquals("search_menu", calls.get(0).name());
        assertEquals("{\"q\":\"pasta\"}", calls.get(0).arguments());
        assertEquals("[\"margherita\"]", calls.get(0).content());
        assertEquals("save_type", calls.get(1).name());
        assertEquals("saved", calls.get(1).content());
    }

    @Test
    void parseToolCallsSkipsOrphanedToolMessagesAndCapsCount() {
        var messages = new java.util.ArrayList<Object>();
        for (int i = 0; i < 30; i++) {
            messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                    Map.of("id", "call-" + i, "type", "function", "function", Map.of("name", "tool-" + i, "arguments", "{}")))));
            messages.add(Map.of("role", "tool", "tool_call_id", "call-" + i, "content", "result-" + i));
        }
        messages.add(Map.of("role", "tool", "tool_call_id", "missing", "content", "orphan"));

        var calls = GatewaySupport.parseToolCalls(bodyOf("messages", messages), 25);

        assertEquals(25, calls.size());
        assertEquals("tool-0", calls.getFirst().name());
        assertEquals("tool-24", calls.getLast().name());
    }

    @Test
    void parseToolCallsReturnsEmptyForNonChatBodies() {
        assertTrue(GatewaySupport.parseToolCalls(bodyOf("model", "gpt-4o"), 20).isEmpty());
        assertTrue(GatewaySupport.parseToolCalls(bodyOf("messages", List.of()), 20).isEmpty());
    }

    @Test
    void isVertexGeminiBaseUrlMatchesAiPlatformOnly() {
        assertTrue(GatewaySupport.isVertexGeminiBaseUrl("https://aiplatform.googleapis.com/v1beta1"));
        assertTrue(GatewaySupport.isVertexGeminiBaseUrl("https://us-aiplatform.googleapis.com"));
        assertFalse(GatewaySupport.isVertexGeminiBaseUrl("https://generativelanguage.googleapis.com/v1beta"));
        assertFalse(GatewaySupport.isVertexGeminiBaseUrl(null));
    }

    @Test
    void geminiOpenAiCompatibleUrlBuildsVertexOpenApiEndpoint() {
        var provider = new ai.core.server.domain.GatewayProviderConfig();
        provider.name = "google";
        provider.baseUrl = "https://aiplatform.googleapis.com/v1beta1";
        provider.vertexProjectId = "my-project";
        provider.vertexLocation = "us-central1";

        assertEquals("https://aiplatform.googleapis.com/v1beta1/projects/my-project/locations/us-central1/endpoints/openapi",
                GatewaySupport.geminiOpenAiCompatibleUrl(provider));
    }

    @Test
    void geminiOpenAiCompatibleUrlVertexRequiresProjectAndLocation() {
        var provider = new ai.core.server.domain.GatewayProviderConfig();
        provider.name = "google";
        provider.baseUrl = "https://aiplatform.googleapis.com/v1beta1";

        assertThrows(core.framework.web.exception.BadRequestException.class, () -> GatewaySupport.geminiOpenAiCompatibleUrl(provider));
    }

    @Test
    void geminiOpenAiCompatibleUrlAppendsOpenAiSegmentToDeveloperApi() {
        var provider = new ai.core.server.domain.GatewayProviderConfig();
        provider.name = "google";
        provider.baseUrl = "https://generativelanguage.googleapis.com/v1beta/";

        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", GatewaySupport.geminiOpenAiCompatibleUrl(provider));
    }

    @Test
    void geminiOpenAiCompatibleUrlKeepsExistingOpenAiSuffix() {
        var provider = new ai.core.server.domain.GatewayProviderConfig();
        provider.name = "google";
        provider.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";

        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", GatewaySupport.geminiOpenAiCompatibleUrl(provider));
    }

    private Map<String, Object> bodyOf(String key, Object value) {
        return Map.of(key, value);
    }

    private Request requestWith(String header, String value) {
        var request = mock(Request.class);
        when(request.header(header)).thenReturn(Optional.ofNullable(value));
        return request;
    }
}
