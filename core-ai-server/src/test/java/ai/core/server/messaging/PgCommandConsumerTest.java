package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class PgCommandConsumerTest {
    private final PgCommandQueue queue = mock(PgCommandQueue.class);
    private final InProcessCommandHandler handler = mock(InProcessCommandHandler.class);
    private final SessionOwnershipRegistry ownershipRegistry = mock(SessionOwnershipRegistry.class);
    private PgCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        when(ownershipRegistry.getHostname()).thenReturn("pod-a");
        consumer = new PgCommandConsumer(queue, handler, ownershipRegistry);
    }

    @Test
    void targetedCommandRunsWhenThisPodStillOwnsTheSession() {
        var command = command();
        var item = new PgCommandQueue.ClaimedCommand(7, command, "pod-a", 1);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(true);

        consumer.process(item);

        verify(handler).handle(command);
        verify(queue).complete(7, "pod-a");
    }

    @Test
    void targetedCommandIsRequeuedWhenOwnershipMoved() {
        var command = command();
        var item = new PgCommandQueue.ClaimedCommand(7, command, "pod-a", 1);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(false);

        consumer.process(item);

        verify(handler, never()).handle(any());
        verify(queue).retryLater(7, "pod-a", 5);
        verify(queue, never()).complete(anyLong(), any());
    }

    @Test
    void unownedCommandIsClaimedLocally() {
        var command = command();
        var item = new PgCommandQueue.ClaimedCommand(7, command, null, 1);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(false);
        when(ownershipRegistry.claim("s-1")).thenReturn(true);

        consumer.process(item);

        verify(handler).handle(command);
        verify(queue).complete(7, "pod-a");
    }

    @Test
    void unownedCommandIsRequeuedWhenAnotherPodOwnsTheSession() {
        var command = command();
        var item = new PgCommandQueue.ClaimedCommand(7, command, null, 1);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(false);
        when(ownershipRegistry.claim("s-1")).thenReturn(false);

        consumer.process(item);

        verify(handler, never()).handle(any());
        verify(queue).retryLater(7, "pod-a", 5);
    }

    @Test
    void handlerFailureIsRecorded() {
        var command = command();
        var item = new PgCommandQueue.ClaimedCommand(7, command, "pod-a", 2);
        when(ownershipRegistry.isOwner("s-1")).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(handler).handle(command);

        consumer.process(item);

        verify(queue).fail(eq(7L), eq("pod-a"), eq(2), any(Throwable.class));
        verify(queue, never()).complete(anyLong(), any());
    }

    private SessionCommand command() {
        return SessionCommand.approveToolCall("s-1", "user", "call", ApprovalDecision.APPROVE);
    }
}
