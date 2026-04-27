package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class AgentSessionChannelListener implements ChannelListener<SseBaseEvent> {
    private static final String SESSION_ID_KEY = "agent-session-id";
    private final Logger logger = LoggerFactory.getLogger(AgentSessionChannelListener.class);

    @Inject
    SessionChannelService sessionChannelService;

    @Override
    public void onConnect(Request request, Channel<SseBaseEvent> channel, String lastEventId) {
        ActionLogContext.triggerTrace(false);
        var sessionId = request.queryParams().get(SESSION_ID_KEY);
        if (sessionId == null || sessionId.isBlank()) {
            channel.close();
            return;
        }

        logger.info("SSE client connected, sessionId={}", sessionId);
        sessionChannelService.connect(channel, sessionId);
        channel.context().put(SESSION_ID_KEY, sessionId);
        channel.join(sessionId);
    }

    @Override
    public void onClose(Channel<SseBaseEvent> channel) {
        var sessionId = (String) channel.context().get(SESSION_ID_KEY);
        logger.info("SSE client disconnected, sessionId={}", sessionId);
        if (sessionId != null) {
            sessionChannelService.close(sessionId);
        }
    }
}
