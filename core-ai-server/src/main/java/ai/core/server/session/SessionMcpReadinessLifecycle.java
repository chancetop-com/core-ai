package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.Node;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.sandbox.SandboxConstants;
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

    public SessionMcpReadinessLifecycle(SandboxService sandboxService, String sessionId) {
        this.sandboxService = sandboxService;
        this.sessionId = sessionId;
    }

    @Override
    public void beforeAgentRun(Node<?> node, AtomicReference<String> query, ExecutionContext executionContext) {
        if (sandboxService != null && sessionId != null) {
            sandboxService.sessionMcpProcesses().awaitStarts(sessionId, SandboxConstants.MCP_TURN_START_WAIT_TIMEOUT_SECONDS);
        }
    }
}

