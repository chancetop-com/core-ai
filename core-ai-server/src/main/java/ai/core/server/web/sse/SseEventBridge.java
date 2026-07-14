package ai.core.server.web.sse;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.BatchToolStartEvent;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.sse.SseBatchToolStartEvent;
import ai.core.api.server.session.sse.SseCompressionEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SsePlanUpdateEvent;
import ai.core.api.server.session.sse.SseReasoningChunkEvent;
import ai.core.api.server.session.sse.SseSandboxEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.api.server.session.sse.SseToolApprovalRequestEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseToolResultEvent;
import ai.core.api.server.session.sse.SseToolStartEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import ai.core.server.messaging.EventPublisher;
import core.framework.json.JSON;

import java.util.Map;

/**
 * @author stephen
 */
public class SseEventBridge implements AgentEventListener {
    private final String sessionId;
    private final EventPublisher eventPublisher;

    public SseEventBridge(String sessionId, EventPublisher eventPublisher) {
        this.sessionId = sessionId;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        var sse = new SseTextChunkEvent();
        sse.content = event.chunk;
        sse.isFinalChunk = Boolean.FALSE;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        var sse = new SseReasoningChunkEvent();
        sse.content = event.chunk;
        sse.isFinalChunk = Boolean.FALSE;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onReasoningComplete(ReasoningCompleteEvent event) {
        var sse = new SseReasoningChunkEvent();
        sse.content = "";
        sse.isFinalChunk = Boolean.TRUE;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onToolStart(ToolStartEvent event) {
        var sse = new SseToolStartEvent();
        sse.callId = event.callId;
        sse.toolName = event.toolName;
        sse.toolNotes = event.diff;
        sse.taskId = event.taskId;
        sse.runInBackground = event.runInBackground;
        if (event.arguments != null) {
            @SuppressWarnings("unchecked")
            var args = JSON.fromJSON(Map.class, event.arguments);
            sse.toolArgs = args;
        }
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        var sse = new SseToolResultEvent();
        sse.callId = event.callId;
        sse.toolName = event.toolName;
        sse.status = event.status;
        sse.result = event.result;
        sse.toolType = event.toolType;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        var sse = new SseToolApprovalRequestEvent();
        sse.callId = event.callId;
        sse.toolName = event.toolName;
        sse.arguments = event.arguments;
        sse.suggestedPattern = event.suggestedPattern;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        var sse = new SseTurnCompleteEvent();
        sse.output = event.output;
        sse.cancelled = event.cancelled;
        sse.maxTurnsReached = event.maxTurnsReached;
        sse.inputTokens = event.inputTokens;
        sse.outputTokens = event.outputTokens;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onError(ErrorEvent event) {
        var sse = new SseErrorEvent();
        sse.message = event.message;
        sse.detail = event.detail;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        var sse = new SseStatusChangeEvent();
        sse.status = event.status;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onPlanUpdate(PlanUpdateEvent event) {
        var sse = new SsePlanUpdateEvent();
        sse.todos = event.todos.stream()
                .map(item -> new SsePlanUpdateEvent.TodoItem(item.content, item.status))
                .toList();
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onCompression(CompressionEvent event) {
        var sse = new SseCompressionEvent();
        sse.beforeCount = event.beforeCount;
        sse.afterCount = event.afterCount;
        sse.completed = event.completed;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onSandbox(SandboxEvent event) {
        var sse = new SseSandboxEvent();
        sse.sandboxId = event.sandboxId;
        sse.sandboxType = event.type;
        sse.message = event.message;
        sse.durationMs = event.durationMs;
        sse.hostname = event.hostname;
        sse.ip = event.ip;
        sse.image = event.image;
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onBatchToolStart(BatchToolStartEvent event) {
        var sse = new SseBatchToolStartEvent();
        sse.group = event.group();
        sse.tools = event.tools().stream()
                .map(ti -> {
                    var t = new SseBatchToolStartEvent.ToolInfo();
                    t.callId = ti.callId();
                    t.toolName = ti.toolName();
                    t.arguments = ti.arguments();
                    return t;
                })
                .toList();
        sse.taskId = event.taskId();
        eventPublisher.publish(sessionId, sse);
    }

    @Override
    public void onEnvironmentOutput(EnvironmentOutputChunkEvent event) {
        var sse = new SseEnvironmentOutputChunkEvent();
        sse.source = event.source;
        sse.callId = event.callId;
        sse.chunk = event.chunk;
        eventPublisher.publish(sessionId, sse);
    }
}
