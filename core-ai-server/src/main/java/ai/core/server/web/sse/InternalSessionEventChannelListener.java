package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pod-to-pod SSE stream of the owner's replay buffer and live events.
 *
 * @author stephen
 */
public class InternalSessionEventChannelListener implements ChannelListener<SseBaseEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalSessionEventChannelListener.class);
    private static final String SUBSCRIPTION_KEY = "session-event-hub-subscription";

    @Inject
    SessionEventHubRegistry hubRegistry;

    @Override
    public void onConnect(Request request, Channel<SseBaseEvent> channel, String lastEventId) {
        ActionLogContext.triggerTrace(false);
        var sessionId = request.queryParams().get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new BadRequestException("session_id is required");
        }
        var after = parseAfter(request.queryParams().get("after"));

        var hub = hubRegistry.getOrCreate(sessionId);
        var subscription = hub.subscribe(after, (seq, event) ->
                channel.send(seq > 0 ? String.valueOf(seq) : null, event));
        channel.context().put(SUBSCRIPTION_KEY, subscription);
        channel.context().put("agent-session-id", sessionId);
        channel.join(sessionId);
        LOGGER.info("internal session event stream connected, sessionId={}, after={}", sessionId, after);
    }

    @Override
    public void onClose(Channel<SseBaseEvent> channel) {
        var subscription = (SessionEventHub.Subscription) channel.context().get(SUBSCRIPTION_KEY);
        if (subscription != null) subscription.close();
        var sessionId = (String) channel.context().get("agent-session-id");
        LOGGER.info("internal session event stream disconnected, sessionId={}", sessionId);
    }

    private long parseAfter(String after) {
        if (after == null || after.isBlank()) return 0;
        try {
            return Long.parseLong(after);
        } catch (NumberFormatException e) {
            throw new BadRequestException("after must be a number", "BAD_REQUEST", e);
        }
    }
}
