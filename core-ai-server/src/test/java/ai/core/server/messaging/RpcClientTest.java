package ai.core.server.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class RpcClientTest {
    private final SessionOwnershipRegistry ownership = mock(SessionOwnershipRegistry.class);
    private final RedisRpcTransport redis = mock(RedisRpcTransport.class);
    private final String ok = RpcClient.okResponse("\"ok\"");

    @Test
    void httpSuccessDoesNotTouchRedis() {
        when(ownership.getOwner("s1")).thenReturn("pod-1");
        RpcTransport http = (target, command, timeout) -> ok;
        var client = new RpcClient(ownership, redis, http);

        assertEquals("ok", client.call(command(), String.class, Duration.ofSeconds(1)));
        verify(redis, never()).call(any(), any(), any());
    }

    @Test
    void undeliveredHttpFallsBackToRedis() {
        when(ownership.getOwner("s1")).thenReturn("pod-1");
        RpcTransport http = (target, command, timeout) -> {
            throw new RpcNotDeliveredException("no address");
        };
        when(redis.call(eq("pod-1"), any(), any())).thenReturn(ok);
        var client = new RpcClient(ownership, redis, http);

        assertEquals("ok", client.call(command(), String.class, Duration.ofSeconds(1)));
        verify(redis).call(eq("pod-1"), any(), any());
    }

    @Test
    void httpBusinessErrorDoesNotFallBack() {
        RpcTransport http = (target, command, timeout) -> {
            throw new RuntimeException("RPC HTTP error, status=401");
        };
        var client = new RpcClient(ownership, redis, http);

        assertThrows(RuntimeException.class,
            () -> client.callToPod("pod-1", command(), String.class, Duration.ofSeconds(1)));
        verify(redis, never()).call(any(), any(), any());
    }

    @Test
    void noOwnerUsesRedisUnownedStream() {
        when(ownership.getOwner("s1")).thenReturn(null);
        when(redis.call(isNull(), any(), any())).thenReturn(ok);
        var client = new RpcClient(ownership, redis, (target, command, timeout) -> {
            throw new AssertionError("http must not be used without an owner");
        });

        assertEquals("ok", client.call(command(), String.class, Duration.ofSeconds(1)));
        verify(redis).call(isNull(), any(), any());
    }

    private SessionCommand command() {
        return new SessionCommand(CommandType.LOAD_SKILLS, "s1", "u1", "{}", "r1");
    }
}
