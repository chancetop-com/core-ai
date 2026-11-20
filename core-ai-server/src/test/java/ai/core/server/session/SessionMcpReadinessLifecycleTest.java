package ai.core.server.session;

import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.SessionMcpProcesses;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author core-ai
 */
class SessionMcpReadinessLifecycleTest {
    @Test
    void waitsForTheSessionMcpStartsBeforeTheRunStarts() {
        var sandboxService = mock(SandboxService.class);
        var mcpProcesses = mock(SessionMcpProcesses.class);
        when(sandboxService.sessionMcpProcesses()).thenReturn(mcpProcesses);
        var lifecycle = new SessionMcpReadinessLifecycle(sandboxService, "session-id");

        lifecycle.beforeAgentRun(null, new AtomicReference<>("hi"), null);

        verify(mcpProcesses).awaitStarts("session-id", SandboxConstants.MCP_TURN_START_WAIT_TIMEOUT_SECONDS);
    }

    @Test
    void doesNothingWithoutASandboxService() {
        var lifecycle = new SessionMcpReadinessLifecycle(null, "session-id");

        assertDoesNotThrow(() -> lifecycle.beforeAgentRun(null, new AtomicReference<>("hi"), null));
    }

    @Test
    void doesNothingWithoutASessionId() {
        var sandboxService = mock(SandboxService.class);
        var lifecycle = new SessionMcpReadinessLifecycle(sandboxService, null);

        assertDoesNotThrow(() -> lifecycle.beforeAgentRun(null, new AtomicReference<>("hi"), null));

        verifyNoInteractions(sandboxService);
    }
}

