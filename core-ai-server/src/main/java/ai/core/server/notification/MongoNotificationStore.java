package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class MongoNotificationStore implements NotificationStore {
    public static MongoNotificationStore of(MongoCollection<Notification> collection) {
        var store = new MongoNotificationStore();
        store.notificationCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<Notification> notificationCollection;

    @Override
    public void insert(Notification notification) {
        notificationCollection.insert(notification);
    }

    @Override
    public List<Notification> list(String userId, NotificationCategory category, NotificationStatus status, int offset, int limit) {
        var query = new Query();
        query.filter = filter(userId, category, status);
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return notificationCollection.find(query);
    }

    @Override
    public long count(String userId, NotificationCategory category, NotificationStatus status) {
        return notificationCollection.count(filter(userId, category, status));
    }

    @Override
    public long unreadCount(String userId) {
        return notificationCollection.count(Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("status", NotificationStatus.UNREAD)));
    }

    @Override
    public void markRead(String notificationId, String userId) {
        notificationCollection.update(
            Filters.and(Filters.eq("_id", notificationId), Filters.eq("user_id", userId)),
            Updates.combine(
                Updates.set("status", NotificationStatus.READ),
                Updates.set("read_at", ZonedDateTime.now())));
    }

    @Override
    public long markAllRead(String userId) {
        return notificationCollection.update(
            Filters.and(Filters.eq("user_id", userId), Filters.eq("status", NotificationStatus.UNREAD)),
            Updates.combine(
                Updates.set("status", NotificationStatus.READ),
                Updates.set("read_at", ZonedDateTime.now())));
    }

    private org.bson.conversions.Bson filter(String userId, NotificationCategory category, NotificationStatus status) {
        var filters = Filters.eq("user_id", userId);
        if (category != null) filters = Filters.and(filters, Filters.eq("category", category));
        if (status != null) filters = Filters.and(filters, Filters.eq("status", status));
        return filters;
    }
}
