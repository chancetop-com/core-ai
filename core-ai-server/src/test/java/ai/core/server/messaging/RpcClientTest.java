package ai.core.server.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class RpcClientTest {
    private final SessionOwnershipRegistry ownership = mock(SessionOwnershipRegistry.class);
    private final String ok = RpcClient.okResponse("\"ok\"");

    @Test
    void resolvesTheOwnerAndCallsThePodDirectly() {
        when(ownership.getOwner("s1")).thenReturn("pod-1");
        RpcTransport http = mock(RpcTransport.class);
        when(http.call(eq("pod-1"), any(), any())).thenReturn(ok);
        var client = new RpcClient(ownership, http);

        assertEquals("ok", client.call(command(), String.class, Duration.ofSeconds(1)));
        verify(http).call(eq("pod-1"), any(), any());
    }

    @Test
    void httpFailureIsNotRetriedOnAnotherTransport() {
        RpcTransport http = (target, command, timeout) -> {
            throw new RpcNotDeliveredException("no address");
        };
        var client = new RpcClient(ownership, http);

        assertThrows(RpcNotDeliveredException.class,
                () -> client.callToPod("pod-1", command(), String.class, Duration.ofSeconds(1)));
    }

    @Test
    void noOwnerIsAnError() {
        when(ownership.getOwner("s1")).thenReturn(null);
        RpcTransport http = mock(RpcTransport.class);
        var client = new RpcClient(ownership, http);

        assertThrows(RpcNotDeliveredException.class,
                () -> client.call(command(), String.class, Duration.ofSeconds(1)));
        verify(http, never()).call(any(), any(), any());
    }

    private SessionCommand command() {
        return new SessionCommand(CommandType.LOAD_SKILLS, "s1", "u1", "{}", "r1");
    }
}
