package ai.core.server.messaging;

import ai.core.server.sandbox.SandboxService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server that receives a request for an unowned session claims and runs it itself. Otherwise
 * another process would race for the command and the turn would run where the user's SSE is not.
 *
 * @author stephen
 */
class CommandPublisherTest {
    private static SessionCommand command(String sessionId) {
        return new SessionCommand(CommandType.SEND_MESSAGE, sessionId, "user-1", "hello", null);
    }

    @Test
    void unownedSessionIsClaimedAndHandledLocally() {
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        var queue = mock(PgCommandQueue.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        when(ownership.claim("s1")).thenReturn(Boolean.TRUE);
        var publisher = new CommandPublisher(ownership, mock(SandboxService.class), handler, true, queue);

        publisher.publish(command("s1"));

        verify(handler).handle(any());
        verify(queue, never()).publish(any(), any());
    }

    @Test
    void lostClaimPublishesToTheSharedQueue() {
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        var queue = mock(PgCommandQueue.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        when(ownership.claim("s1")).thenReturn(Boolean.FALSE);
        var publisher = new CommandPublisher(ownership, mock(SandboxService.class), handler, true, queue);

        publisher.publish(command("s1"));

        verify(queue).publish(any(), isNull());
        verify(handler, never()).handle(any());
    }

    @Test
    void localClaimCanBeSwitchedOffForQueueDistribution() {
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        var queue = mock(PgCommandQueue.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        var publisher = new CommandPublisher(ownership, mock(SandboxService.class), handler, false, queue);

        publisher.publish(command("s1"));

        verify(ownership, never()).claim(any());
        verify(handler, never()).handle(any());
        verify(queue).publish(any(), isNull());
    }

    @Test
    void ownedSessionIsPublishedToItsOwner() {
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        var queue = mock(PgCommandQueue.class);
        when(ownership.getOwner("s1")).thenReturn("pod-2");
        var publisher = new CommandPublisher(ownership, mock(SandboxService.class), handler, true, queue);

        publisher.publish(command("s1"));

        verify(queue).publish(any(), eq("pod-2"));
        verify(handler, never()).handle(any());
    }

    @Test
    void queueFailureSurfacesWhenTheSessionIsNotLocal() {
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        var queue = mock(PgCommandQueue.class);
        when(ownership.getOwner("s1")).thenReturn("pod-2");
        doThrow(new RuntimeException("db down")).when(queue).publish(any(), any());
        var publisher = new CommandPublisher(ownership, mock(SandboxService.class), handler, true, queue);

        assertThrows(RuntimeException.class, () -> publisher.publish(command("s1")));
    }
}
