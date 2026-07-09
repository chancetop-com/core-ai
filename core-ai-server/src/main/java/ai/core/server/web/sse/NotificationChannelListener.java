package ai.core.server.web.sse;

import ai.core.api.server.notification.NotificationSseEvent;
import ai.core.server.notification.NotificationEventPublisher;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE channel listener for real-time notification delivery to a specific user.
 *
 * @author stephen
 */
public class NotificationChannelListener implements ChannelListener<NotificationSseEvent> {
    private static final String USER_ID_KEY = "ntf-user-id";
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationChannelListener.class);

    @Inject
    NotificationEventPublisher eventPublisher;

    @Override
    public void onConnect(Request request, Channel<NotificationSseEvent> channel, String lastEventId) {
        var userId = request.queryParams().get(USER_ID_KEY);
        if (userId == null || userId.isBlank()) {
            channel.close();
            return;
        }
        eventPublisher.connect(userId, channel);
        channel.context().put(USER_ID_KEY, userId);
        LOGGER.debug("notification channel connected, userId={}", userId);
    }

    @Override
    public void onClose(Channel<NotificationSseEvent> channel) {
        var userId = (String) channel.context().get(USER_ID_KEY);
        if (userId != null) {
            eventPublisher.disconnect(userId);
        }
    }
}
