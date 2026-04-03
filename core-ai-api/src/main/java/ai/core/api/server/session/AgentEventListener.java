package ai.core.api.server.session;

/**
 * @author stephen
 */
public interface AgentEventListener {
    default void onTextChunk(TextChunkEvent event) {
    }

    default void onReasoningChunk(ReasoningChunkEvent event) {
    }

    default void onReasoningComplete(ReasoningCompleteEvent event) {
    }

    default void onToolStart(ToolStartEvent event) {
    }

    default void onToolResult(ToolResultEvent event) {
    }

    default void onToolApprovalRequest(ToolApprovalRequestEvent event) {
    }

    default void onTurnComplete(TurnCompleteEvent event) {
    }

    default void onError(ErrorEvent event) {
    }

    default void onStatusChange(StatusChangeEvent event) {
    }
    default void onOnTool(OnToolEvent event) {
    }
    default void onPlanUpdate(PlanUpdateEvent event) {
    }
}
