package ai.core.server.web;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.EventType;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.api.server.session.sse.SseStartEvent;
import core.framework.json.JSON;
import core.framework.web.sse.Channel;

/**
 * @author stephen
 */
public class SseEventBridge implements AgentEventListener {
    private final Channel<SseBaseEvent> channel;

    public SseEventBridge(Channel<SseBaseEvent> channel) {
        this.channel = channel;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        send(EventType.TEXT_CHUNK, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        send(EventType.REASONING_CHUNK, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onReasoningComplete(ReasoningCompleteEvent event) {
        send(EventType.REASONING_COMPLETE, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        send(EventType.TOOL_START, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        send(EventType.TOOL_RESULT, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        send(EventType.TOOL_APPROVAL_REQUEST, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        send(EventType.TURN_COMPLETE, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onError(ErrorEvent event) {
        send(EventType.ERROR, event.sessionId, JSON.toJSON(event));
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        send(EventType.STATUS_CHANGE, event.sessionId, JSON.toJSON(event));
    }

    private void send(EventType type, String sessionId, String data) {
        var sse = new SseStartEvent();
        sse.type = type;
        sse.sessionId = sessionId;
        sse.data = data;
        channel.send(sse);
    }
}
