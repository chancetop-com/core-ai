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
    }
}
