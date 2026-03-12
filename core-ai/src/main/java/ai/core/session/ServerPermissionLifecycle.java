package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Server-side permission lifecycle manager for tool call approval.
 */
public class ServerPermissionLifecycle extends AbstractLifecycle {
    private final Logger logger = LoggerFactory.getLogger(ServerPermissionLifecycle.class);
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final PermissionGate permissionGate;
    private final boolean autoApproveAll;
    private final ToolPermissionStore permissionStore;

    public ServerPermissionLifecycle(String sessionId, Consumer<AgentEvent> dispatcher, PermissionGate permissionGate, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.permissionGate = permissionGate;
        this.autoApproveAll = autoApproveAll;
        this.permissionStore = permissionStore;
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        var callId = functionCall.id;
        var toolName = functionCall.function.name;
        var arguments = functionCall.function.arguments;

        logger.debug("beforeTool: tool={}, callId={}", toolName, callId);

        dispatcher.accept(ToolStartEvent.of(sessionId, callId, toolName, arguments));

        if (autoApproveAll) {
            logger.debug("auto-approve enabled, skipping approval for tool={}, callId={}", toolName, callId);
            return;
        }
        if (permissionStore != null && permissionStore.isApproved(toolName)) {
            logger.debug("tool already approved, skipping approval for tool={}, callId={}", toolName, callId);
            return;
        }

        permissionGate.prepare(callId);
        logger.debug("dispatching approval request: tool={}, callId={}", toolName, callId);

        dispatcher.accept(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments));

        logger.debug("waiting for approval: tool={}, callId={}", toolName, callId);
        var decision = permissionGate.waitForApproval(callId, 300_000);
        logger.debug("approval received: tool={}, callId={}, decision={}", toolName, callId, decision);

        if (decision == ApprovalDecision.DENY) {
            throw new ToolCallDeniedException(toolName);
        }
        if (decision == ApprovalDecision.APPROVE_ALWAYS && permissionStore != null) {
            permissionStore.approve(toolName);
        }
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        var callId = functionCall.id;
        var toolName = functionCall.function.name;
        var status = toolResult.isCompleted() ? "success" : "failure";
        var result = toolResult.getResult();

        logger.debug("afterTool: tool={}, callId={}, status={}", toolName, callId, status);
        dispatcher.accept(ToolResultEvent.of(sessionId, callId, toolName, status, result));
    }
}
