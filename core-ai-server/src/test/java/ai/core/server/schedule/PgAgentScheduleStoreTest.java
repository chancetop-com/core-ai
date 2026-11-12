package ai.core.server.schedule;

import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ConcurrencyPolicy;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgAgentScheduleStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-schedule-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentScheduleRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_schedules ("
                + "id text PRIMARY KEY, agent_id text NOT NULL, user_id text NOT NULL, enabled boolean NOT NULL, "
                + "next_run_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, "
                + "data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent schedule test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgAgentScheduleStore store;

    @BeforeEach
    void setUp() {
        store = new PgAgentScheduleStore();
        store.database = DATABASE;
        clearTable();
    }

    private void clearTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.agent_schedules");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear agent schedule test data", e);
        }
    }

    @Test
    void claimAndRollbackUpdateTheColumnReadBackByGet() {
        var now = ZonedDateTime.now();
        var original = now.minusMinutes(1);
        var next = now.plusMinutes(5);
        var schedule = schedule("schedule-1", original);
        store.insert(schedule);

        assertEquals(List.of("schedule-1"), store.listDue(now).stream().map(s -> s.id).toList());
        assertTrue(store.claim(schedule.id, now, next));
        assertFalse(store.claim(schedule.id, now, next));
        assertEquals(next.toInstant().truncatedTo(ChronoUnit.MILLIS), store.get(schedule.id).orElseThrow().nextRunAt.toInstant().truncatedTo(ChronoUnit.MILLIS));
        assertTrue(store.listDue(now).isEmpty());

        assertTrue(store.rollback(schedule.id, next, original));
        assertEquals(original.toInstant().truncatedTo(ChronoUnit.MILLIS), store.get(schedule.id).orElseThrow().nextRunAt.toInstant().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void listReplaceDeleteAndInsertIfAbsent() {
        var schedule = schedule("schedule-1", ZonedDateTime.now().plusMinutes(5));
        assertTrue(store.insertIfAbsent(schedule));
        assertFalse(store.insertIfAbsent(schedule));
        assertEquals(1, store.listByAgentId("agent-1").size());

        schedule.name = "renamed";
        store.replace(schedule);
        assertEquals("renamed", store.get("schedule-1").orElseThrow().name);
        assertEquals(1, store.list().size());

        store.delete("schedule-1");
        assertTrue(store.get("schedule-1").isEmpty());
    }

    private AgentSchedule schedule(String id, ZonedDateTime nextRunAt) {
        var schedule = new AgentSchedule();
        schedule.id = id;
        schedule.agentId = "agent-1";
        schedule.userId = "user-1";
        schedule.name = id;
        schedule.cronExpression = "0 * * * *";
        schedule.timezone = "UTC";
        schedule.enabled = Boolean.TRUE;
        schedule.concurrencyPolicy = ConcurrencyPolicy.SKIP;
        schedule.nextRunAt = nextRunAt;
        schedule.createdAt = ZonedDateTime.now();
        schedule.updatedAt = schedule.createdAt;
        return schedule;
    }
}


