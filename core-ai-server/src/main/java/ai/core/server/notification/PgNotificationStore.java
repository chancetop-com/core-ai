package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class PgNotificationStore implements NotificationStore {
    private static final String TABLE = "app.notifications";
    private static final String SELECT = "SELECT id, user_id, category, type, title, message, agent_id, session_id, "
        + "status, created_at, read_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, category, type, title, message, "
        + "agent_id, session_id, status, created_at, read_at, data) ";

    private static Notification toNotification(PgNotificationRow row) {
        var notification = JsonUtil.fromJson(Notification.class, row.data);
        notification.id = row.id;
        notification.userId = row.userId;
        notification.category = row.category != null ? NotificationCategory.valueOf(row.category) : null;
        notification.type = ai.core.server.domain.NotificationType.valueOf(row.type);
        notification.title = row.title;
        notification.message = row.message;
        notification.agentId = row.agentId;
        notification.sessionId = row.sessionId;
        notification.status = NotificationStatus.valueOf(row.status);
        notification.createdAt = row.createdAt;
        notification.readAt = row.readAt;
        return notification;
    }

    @Inject
    public Database database;

    @Override
    public void insert(Notification notification) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(notification));
    }

    @Override
    public List<Notification> list(String userId, NotificationCategory category, NotificationStatus status, int offset, int limit) {
        var sql = new StringBuilder(512).append(SELECT).append(" WHERE user_id = ?");
        var params = new ArrayList<>();
        params.add(userId);
        if (category != null) {
            sql.append(" AND category = ?");
            params.add(category.name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return database.select(sql.toString(), PgNotificationRow.class, params.toArray())
            .stream().map(PgNotificationStore::toNotification).toList();
    }

    @Override
    public long count(String userId, NotificationCategory category, NotificationStatus status) {
        var sql = new StringBuilder(512).append("SELECT count(*) AS total FROM ").append(TABLE).append(" WHERE user_id = ?");
        var params = new ArrayList<>();
        params.add(userId);
        if (category != null) {
            sql.append(" AND category = ?");
            params.add(category.name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        return database.selectOne(sql.toString(), PgNotificationCountRow.class, params.toArray())
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public long unreadCount(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE
                + " WHERE user_id = ? AND status = ?", PgNotificationCountRow.class, userId,
                NotificationStatus.UNREAD.name())
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public void markRead(String notificationId, String userId) {
        database.execute("UPDATE " + TABLE + " SET status = ?, read_at = ? WHERE id = ? AND user_id = ?",
            NotificationStatus.READ.name(), ZonedDateTime.now(), notificationId, userId);
    }

    @Override
    public long markAllRead(String userId) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, read_at = ? WHERE user_id = ? AND status = ?",
            NotificationStatus.READ.name(), ZonedDateTime.now(), userId, NotificationStatus.UNREAD.name());
    }

    private Object[] params(Notification notification) {
        return new Object[]{
            notification.id,
            notification.userId,
            notification.category != null ? notification.category.name() : null,
            notification.type != null ? notification.type.name() : null,
            notification.title,
            notification.message,
            notification.agentId,
            notification.sessionId,
            notification.status != null ? notification.status.name() : null,
            notification.createdAt,
            notification.readAt,
            JsonUtil.toJson(notification)
        };
    }
}


