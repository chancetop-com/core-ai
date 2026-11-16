package ai.core.server.state;

import ai.core.server.a2a.A2ATaskSnapshot;
import ai.core.server.a2a.PgA2ATaskRow;
import ai.core.server.a2a.PgA2ATaskSnapshotStore;
import ai.core.server.sandbox.PgSandboxBindingRow;
import ai.core.server.sandbox.PgSandboxBindingStore;
import ai.core.server.session.PgSessionActivityRow;
import ai.core.server.session.PgSessionActivityStore;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgStateStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("state-stores-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgSandboxBindingRow.class);
        database.view(PgSessionActivityRow.class);
        database.view(PgA2ATaskRow.class);
        database.view(PgStateCheckRow.class);
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
            statement.execute("CREATE SCHEMA IF NOT EXISTS messaging");
            statement.execute("CREATE TABLE IF NOT EXISTS app.sandbox_bindings ("
                    + "session_id text PRIMARY KEY, sandbox_id text NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.session_activity ("
                    + "session_id text PRIMARY KEY, last_activity bigint NOT NULL, expires_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.a2a_tasks ("
                    + "task_id text PRIMARY KEY, data jsonb NOT NULL, expires_at timestamptz NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize state store test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private String id;

    @BeforeEach
    void setUp() {
        id = "test-" + UUID.randomUUID();
    }

    @Test
    void schemaCheckPassesWhenTablesExist() {
        var check = new StateSchemaCheck();
        check.database = DATABASE;

        assertDoesNotThrow(check::verify);
    }

    @Test
    void sandboxBindingRoundTrip() {
        var store = new PgSandboxBindingStore();
        store.database = DATABASE;

        assertNull(store.getBinding(id));

        store.saveBinding(id, "sandbox-1");
        assertEquals("sandbox-1", store.getBinding(id));

        store.saveBinding(id, "sandbox-2");
        assertEquals("sandbox-2", store.getBinding(id));

        store.deleteBinding(id);
        assertNull(store.getBinding(id));
        assertDoesNotThrow(() -> store.deleteBindingStrict(id));
    }

    @Test
    void sessionActivityIsVisibleUntilItExpires() {
        var store = new PgSessionActivityStore();
        store.database = DATABASE;

        store.touch(id);
        assertTrue(store.lastActivity(id) > 0);

        DATABASE.execute("UPDATE messaging.session_activity SET expires_at = now() - make_interval(secs => ?) WHERE session_id = ?", 1, id);
        assertEquals(0L, store.lastActivity(id));
    }

    @Test
    void a2aSnapshotIsVisibleUntilItExpires() {
        var store = new PgA2ATaskSnapshotStore();
        store.database = DATABASE;

        var snapshot = new A2ATaskSnapshot();
        snapshot.taskId = id;
        snapshot.contextId = "context-1";
        snapshot.ownerPod = "pod-1";
        snapshot.output = "done";

        store.save(snapshot, Duration.ofMinutes(5));
        var loaded = store.get(id);
        assertEquals("context-1", loaded.contextId);
        assertEquals("pod-1", loaded.ownerPod);
        assertEquals("done", loaded.output);

        DATABASE.execute("UPDATE messaging.a2a_tasks SET expires_at = now() - make_interval(secs => ?) WHERE task_id = ?", 1, id);
        assertNull(store.get(id));
    }
}
