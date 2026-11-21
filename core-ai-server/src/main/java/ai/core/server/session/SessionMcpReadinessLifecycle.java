package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.Node;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.SandboxEventType;
import ai.core.api.server.session.sse.SseSandboxEvent;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.sandbox.SandboxService;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds a session's turn until the sandbox-hosted MCP servers it declared have settled.
 * <p>
 * Chat session creation starts those servers in the background so the create API is not blocked by a
 * cold {@code npx} download. The trade-off is that the first turn can arrive before the tools exist;
 * running it as-is makes the agent answer as if the configured MCP server was missing. Waiting here
 * (before the first tool materialization) keeps the configured tool set intact at the cost of the
 * turn waiting for the same start the session already initiated.
 *
 * @author core-ai
 */
public class SessionMcpReadinessLifecycle extends AbstractLifecycle {
    private final SandboxService sandboxService;
    private final String sessionId;
    private final EventPublisher eventPublisher;

    public SessionMcpReadinessLifecycle(SandboxService sandboxService, String sessionId) {
        this(sandboxService, sessionId, null);
    }

    public SessionMcpReadinessLifecycle(SandboxService sandboxService, String sessionId, EventPublisher eventPublisher) {
        this.sandboxService = sandboxService;
        this.sessionId = sessionId;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void beforeAgentRun(Node<?> node, AtomicReference<String> query, ExecutionContext executionContext) {
        if (sandboxService == null || sessionId == null) return;
        var processes = sandboxService.sessionMcpProcesses();
        var waiting = processes.hasPendingStarts(sessionId);
        if (eventPublisher != null && waiting) {
            publishSandboxEvent(SandboxEventType.CREATING, "Starting session tools...");
        }
        processes.awaitStarts(sessionId, SandboxConstants.MCP_TURN_START_WAIT_TIMEOUT_SECONDS);
        if (eventPublisher != null && waiting) {
            publishSandboxEvent(SandboxEventType.READY, "Session tools are ready");
        }
    }

    private void publishSandboxEvent(SandboxEventType type, String message) {
        var event = new SseSandboxEvent();
        event.sandboxType = type;
        event.message = message;
        eventPublisher.publish(sessionId, event);
    }
}

