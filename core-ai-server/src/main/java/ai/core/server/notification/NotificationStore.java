package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;

import java.util.List;

/**
 * @author stephen
 */
public interface NotificationStore {
    void insert(Notification notification);

    List<Notification> list(String userId, NotificationCategory category, NotificationStatus status, int offset, int limit);

    long count(String userId, NotificationCategory category, NotificationStatus status);

    long unreadCount(String userId);

    void markRead(String notificationId, String userId);

    long markAllRead(String userId);
}
