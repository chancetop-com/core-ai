package ai.core.server.run;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
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
class PgAgentRunStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-run-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentRunRow.class);
        database.view(PgAgentRunCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_runs ("
                + "id text PRIMARY KEY, agent_id text NOT NULL, user_id text NOT NULL, triggered_by text NOT NULL, "
                + "status text NOT NULL, schedule_id text, trace_id text, started_at timestamptz NOT NULL, "
                + "queued_at timestamptz, completed_at timestamptz, has_artifacts boolean NOT NULL DEFAULT false, output text, error text, "
                + "data jsonb NOT NULL)");
            statement.execute("ALTER TABLE app.agent_runs ADD COLUMN IF NOT EXISTS output text");
            statement.execute("ALTER TABLE app.agent_runs ADD COLUMN IF NOT EXISTS error text");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent run test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTable() {
        DATABASE.execute("DELETE FROM app.agent_runs");
    }

    @Test
    void insertFindCountAndTraceLink() {
        var store = new PgAgentRunStore();
        store.database = DATABASE;
        var run = run("run-1", RunStatus.RUNNING);
        store.insert(run);

        assertEquals("run-1", store.get("run-1").orElseThrow().id);
        var query = new AgentRunQuery();
        query.agentId = "agent-1";
        query.statuses = List.of(RunStatus.RUNNING, RunStatus.PENDING);
        assertEquals(1, store.count(query));
        assertEquals(1, store.find(query).size());

        assertTrue(store.claimTraceId("run-1", "trace-1"));
        assertFalse(store.claimTraceId("run-1", "trace-2"));
        assertEquals("trace-1", store.get("run-1").orElseThrow().traceId);
    }

    @Test
    void artifactFilterAndUnfinishedTransitions() {
        var store = new PgAgentRunStore();
        store.database = DATABASE;
        var run = run("run-1", RunStatus.RUNNING);
        run.artifacts = List.of(new AgentRunArtifact());
        store.insert(run);

        var query = new AgentRunQuery();
        query.hasArtifacts = true;
        assertEquals(1, store.find(query).size());

        assertTrue(store.failIfUnfinished("run-1", "interrupted", ZonedDateTime.now()));
        assertFalse(store.failIfUnfinished("run-1", "interrupted", ZonedDateTime.now()));
        assertEquals(RunStatus.FAILED, store.get("run-1").orElseThrow().status);

        var second = run("run-2", RunStatus.RUNNING);
        store.insert(second);
        assertTrue(store.reconcileIfUnfinished("run-2", true, "output", null, ZonedDateTime.now()));
        assertEquals(RunStatus.COMPLETED, store.get("run-2").orElseThrow().status);
        assertEquals("output", store.get("run-2").orElseThrow().output);
    }

    @Test
    void nulCharactersInOutputAndTranscriptAreRemoved() {
        var store = new PgAgentRunStore();
        store.database = DATABASE;
        var run = run("run-1", RunStatus.COMPLETED);
        run.output = "a\0b";
        run.transcript = List.of(new ai.core.server.domain.TranscriptEntry());
        run.transcript.getFirst().content = "x\0y";
        store.insert(run);
        assertEquals("ab", store.get("run-1").orElseThrow().output);
        assertEquals("xy", store.get("run-1").orElseThrow().transcript.getFirst().content);
    }

    private AgentRun run(String id, RunStatus status) {
        var run = new AgentRun();
        run.id = id;
        run.agentId = "agent-1";
        run.userId = "user-1";
        run.triggeredBy = TriggerType.MANUAL;
        run.status = status;
        run.startedAt = ZonedDateTime.now();
        return run;
    }
}



