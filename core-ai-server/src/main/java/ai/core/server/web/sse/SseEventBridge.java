package ai.core.server.web.sse;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.EventType;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.api.server.session.SandboxEvent;

/**
 * @author stephen
 */
public class SseEventBridge implements AgentEventListener {
    private final String sessionId;
    private final SessionChannelService sessionChannelService;

    public SseEventBridge(String sessionId, SessionChannelService sessionChannelService) {
        this.sessionId = sessionId;
        this.sessionChannelService = sessionChannelService;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        sessionChannelService.send(sessionId, EventType.TEXT_CHUNK, event);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        sessionChannelService.send(sessionId, EventType.REASONING_CHUNK, event);
    }

    @Override
    public void onReasoningComplete(ReasoningCompleteEvent event) {
        sessionChannelService.send(sessionId, EventType.REASONING_COMPLETE, event);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        sessionChannelService.send(sessionId, EventType.TOOL_START, event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        sessionChannelService.send(sessionId, EventType.TOOL_RESULT, event);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        sessionChannelService.send(sessionId, EventType.TOOL_APPROVAL_REQUEST, event);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        sessionChannelService.send(sessionId, EventType.TURN_COMPLETE, event);
    }

    @Override
    public void onError(ErrorEvent event) {
        sessionChannelService.send(sessionId, EventType.ERROR, event);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        sessionChannelService.send(sessionId, EventType.STATUS_CHANGE, event);
    }

    @Override
    public void onPlanUpdate(PlanUpdateEvent event) {
        sessionChannelService.send(sessionId, EventType.PLAN_UPDATE, event);
    }

    @Override
    public void onCompression(CompressionEvent event) {
        sessionChannelService.send(sessionId, EventType.COMPRESSION, event);
    }

    @Override
    public void onSandbox(SandboxEvent event) {
        sessionChannelService.send(sessionId, EventType.SANDBOX, event);
    }
}
