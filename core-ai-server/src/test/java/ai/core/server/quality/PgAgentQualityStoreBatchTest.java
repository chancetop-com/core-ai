package ai.core.server.quality;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityRunStatus;
import ai.core.utils.JsonUtil;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgAgentQualityStoreBatchTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-quality-batch-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_quality_runs ("
                + "id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent quality batch test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgAgentQualityStore store;

    @BeforeEach
    void setUp() {
        store = new PgAgentQualityStore();
        store.database = DATABASE;
        clearTable();
    }

    private void clearTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.agent_quality_runs");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear agent quality batch test data", e);
        }
    }

    @Test
    void returnsLatestCompletedAuthorRunPerAgentAndIgnoresWorkflowAndStartedRuns() {
        insert(run("a-old", "agent-a", QualityGateMode.PRECHECK, QualityGateResult.PASSED,
            QualityRunStatus.COMPLETED, 10));
        insert(run("a-fail", "agent-a", QualityGateMode.PUBLISH, QualityGateResult.REJECTED,
            QualityRunStatus.COMPLETED, 11));
        insert(run("a-workflow", "agent-a", QualityGateMode.WORKFLOW, QualityGateResult.REJECTED,
            QualityRunStatus.COMPLETED, 12));
        insert(run("a-started", "agent-a", QualityGateMode.PRECHECK, QualityGateResult.PASSED,
            QualityRunStatus.STARTED, 13));

        insert(run("b-pass", "agent-b", QualityGateMode.FORK, QualityGateResult.PASSED,
            QualityRunStatus.COMPLETED, 10));

        var latest = store.findLatestCompletedRuns(List.of("agent-a", "agent-b"),
            Set.of(QualityGateMode.PRECHECK, QualityGateMode.PUBLISH, QualityGateMode.FORK));

        assertEquals("a-fail", latest.get("agent-a").id);
        assertEquals("b-pass", latest.get("agent-b").id);
    }

    private AgentQualityRun run(String id, String agentId, QualityGateMode mode, QualityGateResult result,
                                QualityRunStatus status, int minute) {
        var run = new AgentQualityRun();
        run.id = id;
        run.agentId = agentId;
        run.mode = mode;
        run.gateResult = result;
        run.runStatus = status;
        run.definitionRevision = minute;
        run.policyRevision = 1;
        run.createdAt = ZonedDateTime.parse("2026-11-24T10:00:00Z").plusMinutes(minute);
        run.finishedAt = run.createdAt.plusSeconds(1);
        return run;
    }

    private void insert(AgentQualityRun run) {
        DATABASE.execute("INSERT INTO app.agent_quality_runs (id, data, updated_at) VALUES (?, ?::jsonb, now())",
            run.id, JsonUtil.toJson(run));
    }
}
