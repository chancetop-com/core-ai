package ai.core.server.schedule;

import ai.core.schedule.ScheduledTask;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgScheduledTaskStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("session-schedule-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgSessionScheduleRow.class);
        database.view(PgSessionScheduleCountRow.class);
        createTable();
        return database;
    }

    private static void createTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.session_schedules ("
                + "id text PRIMARY KEY, session_id text NOT NULL, user_id text NOT NULL, enabled boolean NOT NULL, "
                + "next_run_at timestamptz NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, "
                + "data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize session schedule test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgScheduledTaskStore store;

    @BeforeEach
    void setUp() {
        store = new PgScheduledTaskStore();
        store.database = DATABASE;
        clearTable();
    }

    private void clearTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.session_schedules");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear session schedule test data", e);
        }
    }

    @Test
    void createListCountEnableAndDelete() {
        var created = store.create(task("task-1", ZonedDateTime.now()));
        assertEquals("task-1", created.id);
        assertEquals(1, store.list("session-1").size());
        assertEquals(1, store.listPage(0, 10).size());
        assertEquals(1, store.count());
        assertTrue(store.get("task-1").enabled);

        var disabled = store.updateEnabled("task-1", false);
        assertFalse(disabled.enabled);
        assertNull(store.updateEnabled("missing", false));

        assertTrue(store.delete("task-1"));
        assertFalse(store.delete("task-1"));
        assertNull(store.get("task-1"));
    }

    @Test
    void insertIfAbsentClaimAndFindDue() {
        var now = ZonedDateTime.now();
        var task = task("task-1", now.minusMinutes(1));
        task.nextRunAt = now.minusMinutes(1);
        assertTrue(store.insertIfAbsent(task));
        assertFalse(store.insertIfAbsent(task));
        assertEquals(List.of("task-1"), store.findDue(now).stream().map(t -> t.id).toList());

        var next = now.plusMinutes(5);
        assertTrue(store.claim("task-1", task.nextRunAt, next));
        assertFalse(store.claim("task-1", task.nextRunAt, next));
        assertEquals(next.toInstant().truncatedTo(ChronoUnit.MILLIS),
            store.get("task-1").nextRunAt.toInstant().truncatedTo(ChronoUnit.MILLIS));
        assertTrue(store.findDue(now).isEmpty());
    }

    private ScheduledTask task(String id, ZonedDateTime createdAt) {
        var task = new ScheduledTask();
        task.id = id;
        task.sessionId = "session-1";
        task.userId = "user-1";
        task.name = id;
        task.cronExpression = "0 * * * *";
        task.timezone = "UTC";
        task.enabled = Boolean.TRUE;
        task.createdAt = createdAt;
        task.updatedAt = createdAt;
        return task;
    }
}


