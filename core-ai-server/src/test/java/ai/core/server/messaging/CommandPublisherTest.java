package ai.core.server.messaging;

import ai.core.server.sandbox.SandboxService;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server that receives a request for an unowned session claims and runs it itself. Otherwise any process on the
 * same Redis (a compose stack beside an IDE run) races for the command and the turn runs where the user's SSE is not
 * — trace 3ffc4214: the container claimed the session, its docker sandbox failed, the IDE-connected browser saw nothing.
 *
 * @author stephen
 */
class CommandPublisherTest {
    private static SessionCommand command(String sessionId) {
        return new SessionCommand(CommandType.SEND_MESSAGE, sessionId, "user-1", "hello", null);
    }

    @Test
    void unownedSessionIsClaimedAndHandledLocally() {
        var pool = mock(JedisPool.class);
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        when(ownership.claim("s1")).thenReturn(Boolean.TRUE);
        var publisher = new CommandPublisher(pool, ownership, mock(SandboxService.class), handler);

        publisher.publish(command("s1"));

        verify(handler).handle(any());
        verify(pool, never()).getResource();
    }

    @Test
    void lostClaimFallsThroughToRedis() {
        var pool = mock(JedisPool.class);
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        when(ownership.claim("s1")).thenReturn(Boolean.FALSE);
        var publisher = new CommandPublisher(pool, ownership, mock(SandboxService.class), handler);

        // Redis mock yields no connection: the publish path fails and, with no local ownership, surfaces the failure
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> publisher.publish(command("s1")));
        verify(handler, never()).handle(any());
    }

    @Test
    void localClaimCanBeSwitchedOffForStreamDistribution() {
        var pool = mock(JedisPool.class);
        var ownership = mock(SessionOwnershipRegistry.class);
        var handler = mock(InProcessCommandHandler.class);
        when(ownership.getOwner("s1")).thenReturn(null);
        var publisher = new CommandPublisher(pool, ownership, mock(SandboxService.class), handler, false);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> publisher.publish(command("s1")));
        verify(ownership, never()).claim(any());
        verify(handler, never()).handle(any());
    }
}
