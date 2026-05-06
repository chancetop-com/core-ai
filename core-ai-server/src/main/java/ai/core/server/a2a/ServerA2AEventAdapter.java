package ai.core.server.a2a;

import ai.core.a2a.A2AEventAdapter;
import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.session.AgentSessionManager;

/**
 * Server adapter that mirrors task state changes into the shared task registry.
 *
 * @author xander
 */
final class ServerA2AEventAdapter extends A2AEventAdapter {
    private final A2ATaskState taskState;
    private final ServerA2ARouting routing;
    private final AgentSessionManager sessionManager;

    ServerA2AEventAdapter(A2ATaskState taskState, ServerA2ATaskOptions options,
                          ServerA2ARouting routing, AgentSessionManager sessionManager) {
        super(taskState.taskId, taskState, options.streamSender, options.syncFuture);
        this.taskState = taskState;
        this.routing = routing;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void sendSseEvent(StreamResponse data) {
        super.sendSseEvent(data);
        if (routing.eventRelay != null) {
            routing.eventRelay.publish(taskState.taskId, data);
        }
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        super.onTextChunk(event);
        save();
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        super.onReasoningChunk(event);
        save();
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        super.onToolStart(event);
        save();
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        super.onToolResult(event);
        save();
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        super.onToolApprovalRequest(event);
        save();
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        super.onTurnComplete(event);
        save();
    }

    @Override
    public void onError(ErrorEvent event) {
        super.onError(event);
        save();
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        super.onStatusChange(event);
        save();
    }

    private void save() {
        if (sessionManager != null) {
            sessionManager.touchSession(taskState.contextId);
        }
        if (routing.taskRegistry != null) {
            routing.taskRegistry.save(taskState);
        }
    }
}
