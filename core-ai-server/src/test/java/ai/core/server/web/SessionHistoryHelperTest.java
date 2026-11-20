package ai.core.server.web;

import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.server.domain.ChatMessage;
import ai.core.server.session.ChatMessageService;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionHistoryHelperTest {
    @Test
    void historyResponseIncludesPersistedSandboxState() {
        var service = mock(ChatMessageService.class);
        var message = new ChatMessage();
        message.role = "agent";
        message.content = "done";
        message.sandbox = new ChatMessage.SandboxRecord();
        message.sandbox.sandboxType = "ready";
        message.sandbox.sandboxId = "sandbox-1";
        message.sandbox.message = "Sandbox is ready";
        message.sandbox.durationMs = 1108L;
        message.sandbox.hostname = "sandbox-host";
        message.sandbox.ip = "10.0.65.162";
        message.sandbox.image = "sandbox-runtime:latest";
        when(service.history("s-1")).thenReturn(List.of(message));

        var response = SessionHistoryHelper.build(service, "s-1");

        var sandbox = response.messages.getFirst().sandbox;
        assertNotNull(sandbox);
        assertEquals("ready", sandbox.sandboxType);
        assertEquals("sandbox-1", sandbox.sandboxId);
        assertEquals("Sandbox is ready", sandbox.message);
        assertEquals(1108L, sandbox.durationMs);
        assertEquals("sandbox-host", sandbox.hostname);
        assertEquals("10.0.65.162", sandbox.ip);
        assertEquals("sandbox-runtime:latest", sandbox.image);

        var json = JSON.toJSON(response);
        assertTrue(json.contains("\"sandbox_id\":\"sandbox-1\""));
        assertTrue(json.contains("\"sandbox_type\":\"ready\""));
        var roundTripped = JSON.fromJSON(SessionHistoryResponse.class, json);
        assertEquals("sandbox-1", roundTripped.messages.getFirst().sandbox.sandboxId);
        assertEquals("ready", roundTripped.messages.getFirst().sandbox.sandboxType);
    }

    @Test
    void historyResponseKeepsLegacyMessagesWithoutSandboxCompatible() {
        var service = mock(ChatMessageService.class);
        var message = new ChatMessage();
        message.role = "agent";
        message.content = "legacy";
        when(service.history("s-legacy")).thenReturn(List.of(message));

        var response = SessionHistoryHelper.build(service, "s-legacy");

        assertNull(response.messages.getFirst().sandbox);
        assertNull(response.messages.getFirst().compression);
    }

    @Test
    void historyResponseTruncatesHugeToolPayloadsButKeepsSmallOnes() {
        var service = mock(ChatMessageService.class);
        var message = new ChatMessage();
        message.role = "agent";
        var huge = new ChatMessage.ToolCallRecord();
        huge.callId = "c-1";
        huge.name = "fetch";
        huge.arguments = "a".repeat(10_000);
        huge.result = "r".repeat(1_000_000);
        var small = new ChatMessage.ToolCallRecord();
        small.callId = "c-2";
        small.name = "ok";
        small.arguments = "{\"q\":1}";
        small.result = "done";
        message.tools = List.of(huge, small);
        when(service.history("s-3")).thenReturn(List.of(message));

        var response = SessionHistoryHelper.build(service, "s-3");

        var tools = response.messages.getFirst().tools;
        assertTrue(tools.get(0).arguments.startsWith("aaaa"));
        assertTrue(tools.get(0).arguments.contains("characters truncated"));
        assertTrue(tools.get(0).arguments.length() < 5_000);
        assertTrue(tools.get(0).result.contains("characters truncated"));
        assertTrue(tools.get(0).result.length() < 17_000);
        assertEquals("{\"q\":1}", tools.get(1).arguments);
        assertEquals("done", tools.get(1).result);
    }

    @Test
    void historyResponseCapsTheWholeToolPayloadPerMessage() {
        var service = mock(ChatMessageService.class);
        var message = new ChatMessage();
        message.role = "agent";
        var tools = new java.util.ArrayList<ChatMessage.ToolCallRecord>();
        for (int i = 0; i < 10; i++) {
            var tool = new ChatMessage.ToolCallRecord();
            tool.callId = "c-" + i;
            tool.name = "fetch";
            tool.result = "r".repeat(16_000);
            tools.add(tool);
        }
        message.tools = tools;
        when(service.history("s-4")).thenReturn(List.of(message));

        var response = SessionHistoryHelper.build(service, "s-4");

        int kept = response.messages.getFirst().tools.stream()
                .mapToInt(tool -> Math.min(tool.result.length(), 16_000))
                .sum();
        // the per-message budget is 64k; the truncation markers may add a few hundred chars
        assertTrue(kept <= 65_000);
        assertTrue(response.messages.getFirst().tools.getLast().result.contains("characters truncated"));
    }

    @Test
    void historyResponseIncludesPersistedCompression() {
        var service = mock(ChatMessageService.class);
        var message = new ChatMessage();
        message.role = "agent";
        message.content = "done";
        message.compression = new ChatMessage.CompressionRecord();
        message.compression.beforeCount = 16;
        message.compression.afterCount = 14;
        message.compression.contextTokens = 6979;
        message.compression.maxContextTokens = 8192;
        message.compression.triggerThreshold = 0.8;
        when(service.history("s-2")).thenReturn(List.of(message));

        var response = SessionHistoryHelper.build(service, "s-2");

        var compression = response.messages.getFirst().compression;
        assertNotNull(compression);
        assertEquals(16, compression.beforeCount);
        assertEquals(14, compression.afterCount);
        assertEquals(6979, compression.contextTokens);
        assertEquals(8192, compression.maxContextTokens);
        assertEquals(0.8, compression.triggerThreshold);

        var json = JSON.toJSON(response);
        assertTrue(json.contains("\"before_count\":16"));
        assertTrue(json.contains("\"trigger_threshold\":0.8"));
        var roundTripped = JSON.fromJSON(SessionHistoryResponse.class, json);
        assertEquals(14, roundTripped.messages.getFirst().compression.afterCount);
    }
}
