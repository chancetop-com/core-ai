package ai.core.server.notification;

import ai.core.api.server.notification.NotificationSseEvent;
import ai.core.server.domain.Notification;
import core.framework.web.sse.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process publisher for notification SSE events. Each userId maps to at most
 * one active SSE channel on this pod. Cross-pod delivery via Redis pub/sub can
 * be added later by extending {@code EventSubscriber} with a
 * {@code coreai:notif:*} channel pattern.
 *
 * @author stephen
 */
public class NotificationEventPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationEventPublisher.class);

    private final Map<String, Channel<NotificationSseEvent>> channels = new ConcurrentHashMap<>();

    public void connect(String userId, Channel<NotificationSseEvent> channel) {
        var old = channels.put(userId, channel);
        if (old != null) {
            old.close();
        }
        LOGGER.info("notification SSE connected, userId={}", userId);
    }

    public void disconnect(String userId) {
        var removed = channels.remove(userId);
        if (removed != null) {
            LOGGER.info("notification SSE disconnected, userId={}", userId);
        }
    }

    public void push(String userId, Notification notification) {
        var channel = channels.get(userId);
        if (channel != null) {
            channel.send(toEvent(notification));
        }
    }

    private static NotificationSseEvent toEvent(Notification n) {
        var event = new NotificationSseEvent();
        event.id = n.id;
        event.category = n.category.name();
        event.type = n.type.name();
        event.title = n.title;
        event.message = n.message;
        event.agentId = n.agentId;
        event.sessionId = n.sessionId;
        event.createdAt = n.createdAt;
        return event;
    }
}
