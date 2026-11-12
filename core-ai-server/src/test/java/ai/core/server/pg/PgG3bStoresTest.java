package ai.core.server.pg;

import ai.core.server.asynctask.PgAsyncTaskPersistence;
import ai.core.server.asynctask.PgAsyncTaskRow;
import ai.core.server.domain.BackgroundTask;
import ai.core.server.domain.Notification;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationStatus;
import ai.core.server.domain.NotificationType;
import ai.core.server.notification.PgNotificationCountRow;
import ai.core.server.notification.PgNotificationRow;
import ai.core.server.notification.PgNotificationStore;
import ai.core.server.task.PgBackgroundTaskRow;
import ai.core.server.task.PgBackgroundTaskStore;
import ai.core.server.task.TaskStatus;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgG3bStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("g3b-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgBackgroundTaskRow.class);
        database.view(PgAsyncTaskRow.class);
        database.view(PgNotificationRow.class);
        database.view(PgNotificationCountRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.background_tasks ("
                + "id text PRIMARY KEY, type text, status text, status_text text, claimed_by text, "
                + "started_at timestamptz, completed_at timestamptz, retry_count integer, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.async_tool_tasks ("
                + "id text PRIMARY KEY, data text NOT NULL, updated_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.notifications ("
                + "id text PRIMARY KEY, user_id text NOT NULL, category text, type text NOT NULL, title text NOT NULL, "
                + "message text NOT NULL, agent_id text, session_id text, status text NOT NULL, "
                + "created_at timestamptz NOT NULL, read_at timestamptz, data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize g3b test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.background_tasks");
        DATABASE.execute("DELETE FROM app.async_tool_tasks");
        DATABASE.execute("DELETE FROM app.notifications");
    }

    @Test
    void backgroundTaskClaimRetryAndReplace() {
        var store = new PgBackgroundTaskStore();
        store.database = DATABASE;
        var task = new BackgroundTask();
        task.id = "TRACE:2026-01-01";
        task.type = "TRACE";
        task.status = TaskStatus.FAILED;
        task.claimedBy = "pod-1";
        task.startedAt = ZonedDateTime.now();
        task.retryCount = 0;
        task.logs = List.of("line");

        assertTrue(store.insertIfAbsent(task));
        assertFalse(store.insertIfAbsent(task));
        assertTrue(store.retryClaim(task.id, "pod-2", ZonedDateTime.now()));
        assertFalse(store.retryClaim(task.id, "pod-2", ZonedDateTime.now()));

        var stored = store.get(task.id).orElseThrow();
        assertEquals(TaskStatus.RUNNING, stored.status);
        assertEquals("pod-2", stored.claimedBy);
        assertEquals(1, stored.retryCount);
        assertEquals(List.of("line"), stored.logs);

        stored.status = TaskStatus.SUCCESS;
        store.replace(stored);
        assertEquals(TaskStatus.SUCCESS, store.get(task.id).orElseThrow().status);
        assertEquals(1, store.list("TRACE", 10).size());
    }

    @Test
    void asyncTaskPersistenceRoundTrip() {
        var store = new PgAsyncTaskPersistence();
        store.database = DATABASE;
        store.save("task:1", "{\"state\":\"pending\"}");
        store.save("task:2", "{\"state\":\"done\"}");
        assertEquals("{\"state\":\"pending\"}", store.load("task:1").orElseThrow());
        assertEquals(List.of("task:1", "task:2"), store.listIds("task:"));
        store.delete(List.of("task:1"));
        assertTrue(store.load("task:1").isEmpty());
        store.clear();
        assertTrue(store.listIds("task:").isEmpty());
    }

    @Test
    void notificationListCountAndReadState() {
        var store = new PgNotificationStore();
        store.database = DATABASE;
        var notification = new Notification();
        notification.id = "notification-1";
        notification.userId = "user-1";
        notification.category = NotificationCategory.SYSTEM;
        notification.type = NotificationType.COST_ALERT;
        notification.title = "title";
        notification.message = "message";
        notification.status = NotificationStatus.UNREAD;
        notification.createdAt = ZonedDateTime.now();
        store.insert(notification);

        assertEquals(1, store.list("user-1", null, null, 0, 10).size());
        assertEquals(1, store.count("user-1", NotificationCategory.SYSTEM, NotificationStatus.UNREAD));
        assertEquals(1, store.unreadCount("user-1"));

        store.markRead("notification-1", "user-1");
        assertEquals(0, store.unreadCount("user-1"));
        assertEquals(NotificationStatus.READ, store.list("user-1", null, null, 0, 10).get(0).status);

        notification.id = "notification-2";
        notification.status = NotificationStatus.UNREAD;
        store.insert(notification);
        assertEquals(1, store.markAllRead("user-1"));
        assertEquals(0, store.unreadCount("user-1"));
    }
}

