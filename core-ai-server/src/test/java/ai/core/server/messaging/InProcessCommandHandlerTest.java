package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.server.session.AgentSessionManager;
import ai.core.session.InProcessAgentSession;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InProcessCommandHandlerTest {
    @Test
    void publishesSseErrorWhenSendMessageCommandFails() {
        var deps = new InProcessCommandHandlerDependencies();
        deps.sessionManager = mock(AgentSessionManager.class);
        deps.ownershipRegistry = mock(SessionOwnershipRegistry.class);
        deps.eventPublisher = mock(EventPublisher.class);
        doThrow(new RuntimeException("session missing")).when(deps.sessionManager).getSession("s-1");
        var handler = new InProcessCommandHandler(deps);

        handler.handle(SessionCommand.sendMessage("s-1", "u-1", "hello", null));

        verify(deps.eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseErrorEvent error && "session missing".equals(error.message)));
        verify(deps.eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseStatusChangeEvent status && status.status == SessionStatus.ERROR));
    }

    @Test
    void publishesIdleWhenCancelTurnIsAcknowledged() {
        var deps = new InProcessCommandHandlerDependencies();
        deps.sessionManager = mock(AgentSessionManager.class);
        deps.ownershipRegistry = mock(SessionOwnershipRegistry.class);
        deps.eventPublisher = mock(EventPublisher.class);
        var session = mock(InProcessAgentSession.class);
        when(deps.sessionManager.getSession("s-1")).thenReturn(session);
        var handler = new InProcessCommandHandler(deps);

        handler.handle(SessionCommand.cancelTurn("s-1", "u-1"));

        verify(session).cancelTurn();
        verify(deps.eventPublisher).publish(eq("s-1"),
                argThat(event -> event instanceof SseStatusChangeEvent status && status.status == SessionStatus.IDLE));
    }
}
