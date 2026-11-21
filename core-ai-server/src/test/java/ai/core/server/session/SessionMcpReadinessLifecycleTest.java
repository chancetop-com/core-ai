package ai.core.server.session;

import ai.core.api.server.session.sse.SseSandboxEvent;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.SessionMcpProcesses;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author core-ai
 */
@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
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
    void publishesProgressEventsWhileTheTurnWaitsForTheMcpStarts() {
        var sandboxService = mock(SandboxService.class);
        var mcpProcesses = mock(SessionMcpProcesses.class);
        var eventPublisher = mock(EventPublisher.class);
        when(sandboxService.sessionMcpProcesses()).thenReturn(mcpProcesses);
        when(mcpProcesses.hasPendingStarts("session-id")).thenReturn(true);
        var lifecycle = new SessionMcpReadinessLifecycle(sandboxService, "session-id", eventPublisher);

        lifecycle.beforeAgentRun(null, new AtomicReference<>("hi"), null);

        verify(eventPublisher, times(2)).publish(eq("session-id"), argThat(event -> event instanceof SseSandboxEvent));
        verify(mcpProcesses).awaitStarts("session-id", SandboxConstants.MCP_TURN_START_WAIT_TIMEOUT_SECONDS);
    }

    @Test
    void doesNotPublishProgressEventsWhenNothingIsPending() {
        var sandboxService = mock(SandboxService.class);
        var mcpProcesses = mock(SessionMcpProcesses.class);
        var eventPublisher = mock(EventPublisher.class);
        when(sandboxService.sessionMcpProcesses()).thenReturn(mcpProcesses);
        var lifecycle = new SessionMcpReadinessLifecycle(sandboxService, "session-id", eventPublisher);

        lifecycle.beforeAgentRun(null, new AtomicReference<>("hi"), null);

        verify(eventPublisher, never()).publish(any(), any());
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

