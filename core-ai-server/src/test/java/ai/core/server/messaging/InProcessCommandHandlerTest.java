package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.server.blob.ObjectStorageConfiguration;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.session.InProcessAgentSession;
import redis.clients.jedis.JedisPool;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

class InProcessCommandHandlerTest {
    @Test
    void publishesSseErrorWhenSendMessageCommandFails() {
        var sessionManager = mock(AgentSessionManager.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var eventPublisher = mock(EventPublisher.class);
        doThrow(new RuntimeException("session missing")).when(sessionManager).getSession("s-1");
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null, ownershipRegistry, null, eventPublisher, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);

        handler.handle(SessionCommand.sendMessage("s-1", "u-1", "hello", null));

        verify(eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseErrorEvent error && "session missing".equals(error.message)));
        verify(eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseStatusChangeEvent status && status.status == SessionStatus.ERROR));
    }

    @Test
    void downloadsImageAttachmentsBeforeSendingMessage() {
        var sessionManager = mock(AgentSessionManager.class);
        var chatMessageService = mock(ChatMessageService.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var session = mock(InProcessAgentSession.class);
        when(sessionManager.getSession("s-1")).thenReturn(session);
        var storageService = mock(ObjectStorageService.class);
        when(storageService.downloadObject("uploads", "ai/image.jpg")).thenReturn("image".getBytes(StandardCharsets.UTF_8));
        var storageConfiguration = new ObjectStorageConfiguration();
        storageConfiguration.service = storageService;
        storageConfiguration.multimodalContainer = "uploads";
        var sessionDependencies = new SessionCommandDependencies(sessionManager, chatMessageService, ownershipRegistry,
                null, null, storageConfiguration);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);
        var images = List.of(Map.of(
                "container", "uploads",
                "blobName", "ai/image.jpg",
                "contentType", "image/jpeg",
                "fileName", "image.jpg"));

        handler.handle(SessionCommand.sendMessage("s-1", "u-1", "animate this", null, null, images));

        verify(storageService).downloadObject("uploads", "ai/image.jpg");
        verify(session).sendMessage(eq("animate this"), eq(null), argThat(contents ->
                contents.size() == 1 && "aW1hZ2U=".equals(contents.getFirst().data)));
    }

    @Test
    void publishesIdleWhenCancelTurnIsAcknowledged() {
        var sessionManager = mock(AgentSessionManager.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var eventPublisher = mock(EventPublisher.class);
        var session = mock(InProcessAgentSession.class);
        when(sessionManager.getSession("s-1")).thenReturn(session);
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null, ownershipRegistry, null, eventPublisher, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);

        handler.handle(SessionCommand.cancelTurn("s-1", "u-1"));

        verify(session).cancelTurn();
        verify(eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseStatusChangeEvent status && status.status == SessionStatus.IDLE));
    }
}
