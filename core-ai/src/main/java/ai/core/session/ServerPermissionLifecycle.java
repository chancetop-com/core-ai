package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.session.AgentEvent;
import ai.core.api.session.ApprovalDecision;
import ai.core.api.session.ToolApprovalRequestEvent;
import ai.core.api.session.ToolResultEvent;
import ai.core.api.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * @author stephen
 */
public class ServerPermissionLifecycle extends AbstractLifecycle {
    private final Logger logger = LoggerFactory.getLogger(ServerPermissionLifecycle.class);
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final PermissionGate permissionGate;
    private final boolean autoApproveAll;

    public ServerPermissionLifecycle(String sessionId, Consumer<AgentEvent> dispatcher, PermissionGate permissionGate, boolean autoApproveAll) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.permissionGate = permissionGate;
        this.autoApproveAll = autoApproveAll;
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        var callId = functionCall.id;
        var toolName = functionCall.function.name;
        var arguments = functionCall.function.arguments;

        logger.debug("beforeTool: tool={}, callId={}", toolName, callId);

        // 1. Notify client that tool is about to start
        dispatcher.accept(ToolStartEvent.of(sessionId, callId, toolName, arguments));

        // 2. Handle approval if needed
        if (autoApproveAll) {
            logger.debug("auto-approve enabled, skipping approval for tool={}, callId={}", toolName, callId);
            return;
        }

        // Pre-register future BEFORE dispatching, because dispatch may be synchronous
        // and the listener may call respond() before waitForApproval() creates the future
        permissionGate.prepare(callId);
        logger.debug("dispatching approval request: tool={}, callId={}", toolName, callId);

        // Send approval request
        dispatcher.accept(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments));

        logger.debug("waiting for approval: tool={}, callId={}", toolName, callId);
        // Block and wait for client response (future already exists from prepare())
        var decision = permissionGate.waitForApproval(callId, 300_000); // 5 minutes timeout
        logger.debug("approval received: tool={}, callId={}, decision={}", toolName, callId, decision);

        if (decision == ApprovalDecision.DENY) {
            throw new ToolCallDeniedException(toolName);
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
