package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.a2a.Message;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.session.InProcessAgentSession;
import ai.core.utils.JsonUtil;
import redis.clients.jedis.JedisPool;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
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
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null, ownershipRegistry, null, eventPublisher, null, null);
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
        var storageResolver = mock(ObjectStorageServiceResolver.class);
        when(storageResolver.resolve()).thenReturn(storageService);
        when(storageResolver.multimodalContainer()).thenReturn("uploads");
        var sessionDependencies = new SessionCommandDependencies(sessionManager, chatMessageService, ownershipRegistry,
                null, null, storageResolver, null);
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
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null, ownershipRegistry, null, eventPublisher, null, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);

        handler.handle(SessionCommand.cancelTurn("s-1", "u-1"));

        verify(session).cancelTurn();
        verify(eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseStatusChangeEvent status && status.status == SessionStatus.IDLE));
    }

    @Test
    void a2aResumeRpcPreservesCallerIdentity() {
        var sessionManager = mock(AgentSessionManager.class);
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        var a2aService = mock(ServerA2AService.class);
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null, ownershipRegistry,
                null, null, null, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, a2aService, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);
        when(a2aService.resumeTaskOnOwner(any(Message.class), eq("caller-1")))
                .thenReturn(new ai.core.api.a2a.Task());
        var message = Message.user("approve");
        message.taskId = "task-1";
        var command = new SessionCommand(CommandType.A2A_RESUME_TASK, "session-1", "caller-1",
                JsonUtil.toJson(message), null);

        handler.handle(command);

        verify(a2aService).resumeTaskOnOwner(argThat(value -> "task-1".equals(value.taskId)), eq("caller-1"));
    }

    @Test
    void dynamicSkillRpcPreservesCallerIdentity() {
        var sessionManager = mock(AgentSessionManager.class);
        when(sessionManager.loadSkills("session-1", List.of("skill-1"), "caller-1"))
                .thenReturn(List.of("Admin/skill-1"));
        when(sessionManager.unloadSkills("session-1", List.of("skill-1"), "caller-1"))
                .thenReturn(List.of());
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null,
                mock(SessionOwnershipRegistry.class), null, null, null, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);
        var payload = JsonUtil.toJson(Map.of("skillIds", List.of("skill-1")));

        handler.handle(SessionCommand.loadSkills("session-1", "caller-1", payload, null));
        handler.handle(SessionCommand.unloadSkills("session-1", "caller-1", payload, null));

        verify(sessionManager).loadSkills("session-1", List.of("skill-1"), "caller-1");
        verify(sessionManager).unloadSkills("session-1", List.of("skill-1"), "caller-1");
    }

    @Test
    void emptyDynamicSkillLoadRpcStillPreservesCallerForSessionAuthorization() {
        var sessionManager = mock(AgentSessionManager.class);
        var sessionDependencies = new SessionCommandDependencies(sessionManager, null,
                mock(SessionOwnershipRegistry.class), null, null, null, null);
        var rpcDependencies = new CommandRpcDependencies(null, null, null, mock(JedisPool.class), null);
        var handler = new InProcessCommandHandler(sessionDependencies, rpcDependencies);
        var payload = JsonUtil.toJson(Map.of("skillIds", List.of()));

        handler.handle(SessionCommand.loadSkills("session-1", "caller-1", payload, null));

        verify(sessionManager).loadSkills("session-1", List.of(), "caller-1");
    }
}
