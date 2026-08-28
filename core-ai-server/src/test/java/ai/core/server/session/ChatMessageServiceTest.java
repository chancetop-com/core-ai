package ai.core.server.session;

import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageServiceTest {
    @Test
    void userMessagePersistsContentAndUpdatesExistingRegistryRow() {
        var service = service();
        when(service.chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

        service.writeUserMessage("s-1", "hello");

        var captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(service.chatMessageCollection).insert(captor.capture());
        assertEquals("s-1", captor.getValue().sessionId);
        assertEquals("user", captor.getValue().role);
        assertEquals("hello", captor.getValue().content);
        assertEquals(1L, captor.getValue().seq);
        verify(service.sessionRegistry).recordUserMessage("s-1", "hello");
    }

    @Test
    void completedAgentTurnPersistsContentAndUpdatesExistingRegistryRow() {
        var service = service();
        when(service.chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

        service.listener("s-1").onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        var captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(service.chatMessageCollection).insert(captor.capture());
        assertEquals("agent", captor.getValue().role);
        assertEquals("done", captor.getValue().content);
        verify(service.sessionRegistry).recordAgentMessage("s-1");
    }

    @Test
    void completedAgentTurnPersistsLatestSandboxStateForHistoryReload() {
        var service = service();
        when(service.chatMessageCollection.find(any(Query.class))).thenReturn(List.of());
        var listener = service.listener("s-1");

        listener.onSandbox(SandboxEvent.creating("s-1", "sandbox-1"));
        listener.onSandbox(SandboxEvent.ready("s-1", "sandbox-1", 1108L,
                "sandbox-host", "10.0.65.162", "sandbox-runtime:latest"));
        listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        var captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(service.chatMessageCollection).insert(captor.capture());
        var sandbox = captor.getValue().sandbox;
        assertNotNull(sandbox);
        assertEquals("ready", sandbox.sandboxType);
        assertEquals("sandbox-1", sandbox.sandboxId);
        assertEquals("Sandbox is ready", sandbox.message);
        assertEquals(1108L, sandbox.durationMs);
        assertEquals("sandbox-host", sandbox.hostname);
        assertEquals("10.0.65.162", sandbox.ip);
        assertEquals("sandbox-runtime:latest", sandbox.image);
    }

    @Test
    void metadataReadsComeFromDurableRegistry() {
        var service = service();
        var stored = new ChatSession();
        stored.id = "s-1";
        when(service.sessionRegistry.get("s-1")).thenReturn(stored);

        assertSame(stored, service.getSessionMeta("s-1"));
    }

    private ChatMessageService service() {
        var service = new ChatMessageService();
        @SuppressWarnings("unchecked")
        MongoCollection<ChatMessage> collection = mock(MongoCollection.class);
        service.chatMessageCollection = collection;
        service.sessionRegistry = mock(SessionRegistry.class);
        return service;
    }
}
