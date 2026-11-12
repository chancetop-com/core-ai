package ai.core.server.notification;

import ai.core.server.domain.Notification;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class NotificationMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationMigrationService.class);
    private static final String MARKER = "notifications_v1";
    private static final String LOCK_KEY = "notification_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<Notification> notificationCollection;
    @Inject
    NotificationStore notificationStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgNotificationMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("notification migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("notification migration finished, notifications={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgNotificationMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<Notification> page = notificationCollection.find(query);
            if (page.isEmpty()) return count;
            for (var notification : page) {
                database.execute("INSERT INTO app.notifications (id, user_id, category, type, title, message, "
                        + "agent_id, session_id, status, created_at, read_at, data) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
                    notification.id, notification.userId,
                    notification.category != null ? notification.category.name() : null,
                    notification.type != null ? notification.type.name() : null,
                    notification.title, notification.message, notification.agentId, notification.sessionId,
                    notification.status != null ? notification.status.name() : null,
                    notification.createdAt, notification.readAt, JsonSanitizer.json(JsonUtil.toJson(notification)));
                lastId = notification.id;
                count++;
            }
        }
    }
}


