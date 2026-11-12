package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import ai.core.server.domain.NotificationType;
import core.framework.inject.Inject;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class NotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    @Inject
    NotificationStore notificationStore;
    @Inject
    NotificationEventPublisher eventPublisher;

    public Notification create(String userId, NotificationCategory category, NotificationType type,
                                String title, String message, CreateContext ctx) {
        var notification = new Notification();
        notification.id = new ObjectId().toHexString();
        notification.userId = userId;
        notification.category = category;
        notification.type = type;
        notification.title = title;
        notification.message = message;
        notification.agentId = ctx.agentId;
        notification.sessionId = ctx.sessionId;
        notification.status = NotificationStatus.UNREAD;
        notification.createdAt = ZonedDateTime.now();
        notificationStore.insert(notification);
        LOGGER.info("notification created, id={}, userId={}, type={}, title={}", notification.id, userId, type, title);

        eventPublisher.push(userId, notification);
        return notification;
    }

    public List<Notification> list(String userId, NotificationCategory category,
                                    NotificationStatus status, int offset, int limit) {
        return notificationStore.list(userId, category, status, offset, limit);
    }

    public long count(String userId, NotificationCategory category, NotificationStatus status) {
        return notificationStore.count(userId, category, status);
    }

    public long unreadCount(String userId) {
        return notificationStore.unreadCount(userId);
    }

    public void markRead(String notificationId, String userId) {
        notificationStore.markRead(notificationId, userId);
        LOGGER.debug("notification marked read, id={}, userId={}", notificationId, userId);
    }

    public void markAllRead(String userId) {
        long updated = notificationStore.markAllRead(userId);
        LOGGER.info("notifications marked all read, userId={}, count={}", userId, updated);
    }

    public record CreateContext(String agentId, String sessionId) {
    }
}



