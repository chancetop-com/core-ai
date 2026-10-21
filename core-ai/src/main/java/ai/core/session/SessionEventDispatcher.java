package ai.core.session;

import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.BatchToolStartEvent;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;

/**
 * @author stephen
 */
final class SessionEventDispatcher {

    static void dispatch(AgentEventListener listener, AgentEvent event) {
        switch (event) {
            case TextChunkEvent e -> listener.onTextChunk(e);
            case ReasoningChunkEvent e -> listener.onReasoningChunk(e);
            case ReasoningCompleteEvent e -> listener.onReasoningComplete(e);
            case ToolStartEvent e -> listener.onToolStart(e);
            case ToolResultEvent e -> listener.onToolResult(e);
            case ToolApprovalRequestEvent e -> listener.onToolApprovalRequest(e);
            case TurnCompleteEvent e -> listener.onTurnComplete(e);
            case ErrorEvent e -> listener.onError(e);
            case StatusChangeEvent e -> listener.onStatusChange(e);
            case OnToolEvent e -> listener.onOnTool(e);
            case PlanUpdateEvent e -> listener.onPlanUpdate(e);
            case CompressionEvent e -> listener.onCompression(e);
            case SandboxEvent e -> listener.onSandbox(e);
            case EnvironmentOutputChunkEvent e -> listener.onEnvironmentOutput(e);
            case BatchToolStartEvent e -> listener.onBatchToolStart(e);
            case TaskStatusEvent e -> listener.onTaskStatus(e);
            case CustomEvent e -> listener.onCustomEvent(e);
            default -> {
            }
        }
    }

    private SessionEventDispatcher() {
    }
}
