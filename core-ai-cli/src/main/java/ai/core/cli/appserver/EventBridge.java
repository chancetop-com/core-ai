package ai.core.cli.appserver;

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
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards AgentEventListener callbacks to the client as {@code session/event} notifications. The
 * bridge mirrors the listener interface in full (not a hand-picked subset) with a type tag, so new
 * engine event types reach clients without an engine upgrade; unknown types are the client's to ignore.
 *
 * @author stephen
 */
public class EventBridge implements AgentEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBridge.class);

    private final String sessionId;
    private final NotificationSink sink;

    public EventBridge(String sessionId, NotificationSink sink) {
        this.sessionId = sessionId;
        this.sink = sink;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        forward("text_chunk", event);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        forward("reasoning_chunk", event);
    }

    @Override
    public void onReasoningComplete(ReasoningCompleteEvent event) {
        forward("reasoning_complete", event);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        forward("tool_start", event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        forward("tool_result", event);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        forward("tool_approval_request", event);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        forward("turn_complete", event);
    }

    @Override
    public void onError(ErrorEvent event) {
        forward("error", event);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        forward("status_change", event);
    }

    @Override
    public void onOnTool(OnToolEvent event) {
        forward("on_tool", event);
    }

    @Override
    public void onPlanUpdate(PlanUpdateEvent event) {
        forward("plan_update", event);
    }

    @Override
    public void onCompression(CompressionEvent event) {
        forward("compression", event);
    }

    @Override
    public void onSandbox(SandboxEvent event) {
        forward("sandbox", event);
    }

    @Override
    public void onEnvironmentOutput(EnvironmentOutputChunkEvent event) {
        forward("environment_output_chunk", event);
    }

    @Override
    public void onBatchToolStart(BatchToolStartEvent event) {
        forward("batch_tool_start", event);
    }

    @Override
    public void onTaskStatus(TaskStatusEvent event) {
        forward("task_status", event);
    }

    @Override
    public void onCustomEvent(CustomEvent event) {
        forward("custom", event);
    }

    private void forward(String type, AgentEvent event) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.valueToTree(event);
            if (!(node instanceof ObjectNode object)) {
                LOGGER.warn("unexpected non-object event payload, type={}, sessionId={}", type, sessionId);
                return;
            }
            object.put("type", type);
            var params = JsonUtil.OBJECT_MAPPER.createObjectNode();
            params.put("sessionId", sessionId);
            params.set("event", object);
            sink.notify("session/event", params);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to forward session event, type={}, sessionId={}", type, sessionId, e);
        }
    }
}
