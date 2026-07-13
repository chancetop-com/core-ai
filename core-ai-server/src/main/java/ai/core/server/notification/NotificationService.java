package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import ai.core.server.domain.NotificationType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
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
    MongoCollection<Notification> notificationCollection;
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
        notificationCollection.insert(notification);
        LOGGER.info("notification created, id={}, userId={}, type={}, title={}", notification.id, userId, type, title);

        eventPublisher.push(userId, notification);
        return notification;
    }

    public List<Notification> list(String userId, NotificationCategory category,
                                    NotificationStatus status, int offset, int limit) {
        var filters = Filters.eq("user_id", userId);
        if (category != null) {
            filters = Filters.and(filters, Filters.eq("category", category));
        }
        if (status != null) {
            filters = Filters.and(filters, Filters.eq("status", status));
        }
        var query = new Query();
        query.filter = filters;
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return notificationCollection.find(query);
    }

    public long count(String userId, NotificationCategory category, NotificationStatus status) {
        var filters = Filters.eq("user_id", userId);
        if (category != null) {
            filters = Filters.and(filters, Filters.eq("category", category));
        }
        if (status != null) {
            filters = Filters.and(filters, Filters.eq("status", status));
        }
        return notificationCollection.count(filters);
    }

    public long unreadCount(String userId) {
        var filters = Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("status", NotificationStatus.UNREAD));
        return notificationCollection.count(filters);
    }

    public void markRead(String notificationId, String userId) {
        long updated = notificationCollection.update(
            Filters.and(Filters.eq("_id", notificationId), Filters.eq("user_id", userId)),
            Updates.combine(
                Updates.set("status", NotificationStatus.READ),
                Updates.set("read_at", ZonedDateTime.now())));
        if (updated > 0) {
            LOGGER.debug("notification marked read, id={}, userId={}", notificationId, userId);
        }
    }

    public void markAllRead(String userId) {
        long updated = notificationCollection.update(
            Filters.and(Filters.eq("user_id", userId), Filters.eq("status", NotificationStatus.UNREAD)),
            Updates.combine(
                Updates.set("status", NotificationStatus.READ),
                Updates.set("read_at", ZonedDateTime.now())));
        LOGGER.info("notifications marked all read, userId={}, count={}", userId, updated);
    }

    public record CreateContext(String agentId, String sessionId) {
    }
}
