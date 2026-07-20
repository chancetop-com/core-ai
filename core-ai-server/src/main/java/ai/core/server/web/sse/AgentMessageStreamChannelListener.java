package ai.core.server.web.sse;

import ai.core.api.server.session.SendMessageRequest;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.web.AttachmentMessageHelper;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Single-request endpoint that combines message sending with SSE streaming.
 * The client sends a POST with the message body and receives SSE events
 * on the same HTTP connection, eliminating the timing gap between the
 * old SSE-connect and POST requests that caused cross-pod event loss.
 */
public class AgentMessageStreamChannelListener implements ChannelListener<SseBaseEvent> {
    private static final String SESSION_ID_KEY = "agent-session-id";
    private final Logger logger = LoggerFactory.getLogger(AgentMessageStreamChannelListener.class);

    @Inject
    SessionChannelService sessionChannelService;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    WebContext webContext;

    @Override
    public void onConnect(Request request, Channel<SseBaseEvent> channel, String lastEventId) {
        ActionLogContext.triggerTrace(false);
        var sessionId = request.queryParams().get(SESSION_ID_KEY);
        if (sessionId == null || sessionId.isBlank()) {
            channel.close();
            return;
        }

        var body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
        var json = new String(body, StandardCharsets.UTF_8);
        var sendRequest = JsonUtil.fromJson(SendMessageRequest.class, json);

        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        // Register SSE channel BEFORE publishing command — this creates the stateMap entry
        // so that when the owning pod's agent produces events (via Redis Pub/Sub),
        // SessionChannelService.send() finds the session and delivers events directly.
        // Without this ordering, the old two-request flow had a race where RUNNING events
        // could arrive before the SSE channel was registered and be silently dropped.
        sessionChannelService.connect(channel, sessionId);
        channel.context().put(SESSION_ID_KEY, sessionId);
        channel.join(sessionId);
        logger.info("SSE stream connected, sessionId={}", sessionId);

        // Build and publish command — same logic as AgentSessionWebServiceImpl.sendMessage()
        var pendingFiles = AttachmentMessageHelper.collectPendingFiles(sessionId, sendRequest);
        var imageAttachments = AttachmentMessageHelper.collectImageAttachments(sendRequest);
        var message = AttachmentMessageHelper.buildMessageWithAttachments(sendRequest);
        var variables = sendRequest.variables != null ? new HashMap<String, Object>(sendRequest.variables) : null;
        var command = SessionCommand.sendMessage(sessionId, userId, message, variables, pendingFiles, imageAttachments);
        commandPublisher.publish(command);
    }

    @Override
    public void onClose(Channel<SseBaseEvent> channel) {
        var sessionId = (String) channel.context().get(SESSION_ID_KEY);
        logger.info("SSE stream disconnected, sessionId={}", sessionId);
        if (sessionId != null) {
            sessionChannelService.closeIfCurrent(sessionId, channel);
        }
    }
}
