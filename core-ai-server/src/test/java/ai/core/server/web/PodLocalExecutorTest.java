package ai.core.server.web;

import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class PodLocalExecutorTest {
    private static final String SESSION_ID = "s1";

    private static SessionCommand command() {
        return SessionCommand.sandboxCatalog(SESSION_ID, "u1", "req-1");
    }

    private PodLocalExecutor executor;
    private SessionOwnershipRegistry ownershipRegistry;
    private RpcClient rpcClient;

    @BeforeEach
    void setUp() {
        executor = new PodLocalExecutor();
        ownershipRegistry = mock(SessionOwnershipRegistry.class);
        rpcClient = mock(RpcClient.class);
        executor.ownershipRegistry = ownershipRegistry;
        executor.rpcClient = rpcClient;
    }

    @Test
    void ownedSessionRunsTheLocalOperation() {
        when(ownershipRegistry.isOwner(SESSION_ID)).thenReturn(Boolean.TRUE);

        var result = executor.execute(SESSION_ID, () -> "local", command(), String.class);

        assertEquals("local", result);
        verify(rpcClient, never()).call(any(), any(), any());
    }

    @Test
    void foreignSessionIsForwardedOverRpc() {
        when(ownershipRegistry.isOwner(SESSION_ID)).thenReturn(Boolean.FALSE);
        doReturn("remote").when(rpcClient).call(any(), eq(String.class), any());

        var result = executor.execute(SESSION_ID, () -> "local", command(), String.class);

        assertEquals("remote", result);
        verify(rpcClient).call(eq(command()), eq(String.class), eq(PodLocalExecutor.DEFAULT_TIMEOUT));
    }

    @Test
    void customTimeoutIsPassedToTheRpcCall() {
        when(ownershipRegistry.isOwner(SESSION_ID)).thenReturn(Boolean.FALSE);
        doReturn("remote").when(rpcClient).call(any(), eq(String.class), any());

        executor.execute(SESSION_ID, () -> "local", command(), String.class, Duration.ofSeconds(605));

        verify(rpcClient).call(any(), eq(String.class), eq(Duration.ofSeconds(605)));
    }

    @Test
    void withoutAnOwnershipRegistryEverythingRunsLocally() {
        var standalone = new PodLocalExecutor();
        standalone.rpcClient = rpcClient;

        assertTrue(standalone.isOwner(SESSION_ID));
        assertNull(standalone.getOwner(SESSION_ID));
        assertEquals("local", standalone.getHostname());
        assertEquals("local", standalone.execute(SESSION_ID, () -> "local", command(), String.class));
        verify(rpcClient, never()).call(any(), any(), any());
    }
}
