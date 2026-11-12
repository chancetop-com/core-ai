package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgHubCallStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("hub-call-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgHubCallRow.class);
        database.view(PgHubCallCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.hub_calls ("
                + "id text PRIMARY KEY, kind text NOT NULL, user_id text NOT NULL, user_type text, source text, "
                + "target text, ref_id text, group_name text, name text, sandbox_id text, tool_kind text, session_id text, "
                + "args_hash text, args_preview text, success boolean, is_error boolean, status_code integer, "
                + "duration_ms bigint, output_bytes integer, error_message text, task_id text, context_id text, "
                + "input_tokens bigint, output_tokens bigint, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize hub call test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTable() {
        DATABASE.execute("DELETE FROM app.hub_calls");
    }

    @Test
    void insertFinishAttachAndList() {
        var store = new PgHubCallStore();
        store.database = DATABASE;
        var call = call("call-1");
        store.insert(call);
        store.finish(call.id, 12L, 100, true, 200, null);
        store.attachRun(call.id, "task-1", "context-1", 10L, 20L);
        store.attachResult(call.id, "ref-1", null, null);

        var filter = new HubCallListFilter();
        filter.startFrom = ZonedDateTime.now().minusHours(1);
        filter.kind = "mcp_tool";
        filter.offset = 0;
        filter.limit = 10;
        var page = store.list(filter);
        assertEquals(1, page.total());
        var stored = page.calls().getFirst();
        assertTrue(stored.success);
        assertEquals(12L, stored.durationMs);
        assertEquals("task-1", stored.taskId);
        assertEquals("ref-1", stored.refId);
    }

    @Test
    void deleteOlderThanRemovesExpiredRows() {
        var store = new PgHubCallStore();
        store.database = DATABASE;
        store.insert(call("call-1"));
        assertEquals(1, store.deleteOlderThan(ZonedDateTime.now().plusMinutes(1)));
    }

    private HubCall call(String id) {
        var call = new HubCall();
        call.id = id;
        call.kind = "mcp_tool";
        call.userId = "user-1";
        call.source = "test";
        call.createdAt = ZonedDateTime.now();
        return call;
    }
}
