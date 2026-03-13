package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

        if (permissionStore != null) {
            Map<String, Object> argMap = parseArguments(arguments);
            var result = permissionStore.checkPermission(toolName, argMap);
            if (result.isPresent() && result.get()) {
                logger.debug("rule matched ALLOW for tool={}, callId={}", toolName, callId);
                return;
            }
            if (result.isPresent()) {
                logger.debug("rule matched DENY for tool={}, callId={}", toolName, callId);
                throw new ToolCallDeniedException(toolName);
            }
        }

        permissionGate.prepare(callId);
        logger.debug("dispatching approval request: tool={}, callId={}", toolName, callId);
        dispatcher.accept(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments));

        logger.debug("waiting for approval: tool={}, callId={}", toolName, callId);
        var decision = permissionGate.waitForApproval(callId, 300_000);
        logger.debug("approval received: tool={}, callId={}, decision={}", toolName, callId, decision);

        switch (decision) {
            case DENY -> throw new ToolCallDeniedException(toolName);
            case DENY_ALWAYS -> {
                if (permissionStore != null) {
                    permissionStore.deny(toolName, null);
                }
                throw new ToolCallDeniedException(toolName);
            }
            case APPROVE_ALWAYS -> {
                if (permissionStore != null) {
                    permissionStore.allow(toolName, null);
                }
            }
            default -> {
                // APPROVE: single-time, no persistence
            }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return JsonUtil.fromJson(Map.class, arguments);
        } catch (Exception e) {
            logger.debug("failed to parse arguments for rule matching: {}", e.getMessage());
            return Map.of();
        }
    }
}
