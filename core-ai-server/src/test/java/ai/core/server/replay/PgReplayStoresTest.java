package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySample;
import ai.core.server.replay.domain.ReplaySampleStatus;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION"})
class PgReplayStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("replay-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgReplayExperimentRow.class);
        database.view(PgReplayExperimentCountRow.class);
        database.view(PgReplayRunRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.replay_experiments ("
                + "id text PRIMARY KEY, user_id text, origin text, trace_id text, span_id text, agent_id text, "
                + "agent_name text, session_id text, trace_source text, span_name text, original_model text, "
                + "run_count integer, note text, draft_request text, created_at timestamptz, updated_at timestamptz, "
                + "data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.replay_runs ("
                + "id text PRIMARY KEY, experiment_id text NOT NULL, user_id text, label text, model text, "
                + "temperature double precision, reasoning_effort text, sample_count integer, status text, "
                + "created_at timestamptz, completed_at timestamptz, request text, data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize replay test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.replay_runs");
        DATABASE.execute("DELETE FROM app.replay_experiments");
    }

    @Test
    void experimentFiltersIncrementAndBlankCleanup() {
        var store = new PgReplayExperimentStore();
        store.database = DATABASE;
        var blank = experiment("exp-blank", "user-1", ReplayExperimentOrigin.BLANK);
        blank.draftRequest = "{\"messages\":[]}";
        var span = experiment("exp-span", "user-1", ReplayExperimentOrigin.SPAN);
        span.agentId = "agent-1";
        store.insert(blank);
        store.insert(span);

        assertEquals(2, store.find("user-1", false, null, null, 0, 20).size());
        assertEquals(1, store.count("user-1", false, "agent-1", ReplayExperimentOrigin.SPAN));
        assertEquals(ReplayExperimentOrigin.SPAN, store.get("exp-span").orElseThrow().origin);

        store.incrementRunCount("exp-blank", ZonedDateTime.now());
        assertEquals(1, store.get("exp-blank").orElseThrow().runCount);

        var untouched = experiment("exp-untouched", "user-1", ReplayExperimentOrigin.BLANK);
        untouched.draftRequest = "{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}";
        untouched.createdAt = ZonedDateTime.now().minusDays(10);
        store.insert(untouched);
        assertEquals(1, store.deleteUntouchedBlankBefore(ZonedDateTime.now().minusDays(7),
            "{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}"));
        assertTrue(store.get("exp-untouched").isEmpty());
    }

    @Test
    void runSampleUpdatesAndSummaryProjection() {
        var store = new PgReplayRunStore();
        store.database = DATABASE;
        var run = run("run-1");
        store.insert(run);

        var update = new ReplaySampleUpdate();
        update.status = ReplaySampleStatus.COMPLETED;
        update.output = "hello";
        update.inputTokens = 10L;
        update.outputTokens = 20L;
        update.durationMs = 30L;
        update.replayTraceId = "trace-1";
        store.updateSample("run-1", 1, update);

        var loaded = store.get("run-1").orElseThrow();
        assertEquals(ReplaySampleStatus.COMPLETED, loaded.samples.get(1).status);
        assertEquals("hello", loaded.samples.get(1).output);
        assertEquals(10L, loaded.samples.get(1).inputTokens);
        assertEquals("trace-1", loaded.samples.get(1).replayTraceId);

        store.cancelRunningSamples("run-1");
        assertEquals(ReplaySampleStatus.CANCELLED, store.get("run-1").orElseThrow().samples.get(0).status);

        store.complete("run-1", ReplayRunStatus.PARTIAL, ZonedDateTime.now());
        var summary = store.summaries("exp-1").getFirst();
        assertEquals(ReplayRunStatus.PARTIAL, summary.status);
        assertNull(summary.request);
        assertNull(summary.samples);
    }

    private ReplayExperiment experiment(String id, String userId, ReplayExperimentOrigin origin) {
        var experiment = new ReplayExperiment();
        experiment.id = id;
        experiment.userId = userId;
        experiment.origin = origin;
        experiment.runCount = 0;
        experiment.createdAt = ZonedDateTime.now();
        experiment.updatedAt = ZonedDateTime.now();
        return experiment;
    }

    private ReplayRun run(String id) {
        var run = new ReplayRun();
        run.id = id;
        run.experimentId = "exp-1";
        run.userId = "user-1";
        run.label = "variant";
        run.request = "{\"messages\":[]}";
        run.sampleCount = 2;
        var samples = new ArrayList<ReplaySample>();
        for (int i = 0; i < 2; i++) {
            var sample = new ReplaySample();
            sample.index = i;
            sample.status = ReplaySampleStatus.RUNNING;
            samples.add(sample);
        }
        run.samples = samples;
        run.status = ReplayRunStatus.RUNNING;
        run.createdAt = ZonedDateTime.now();
        return run;
    }
}
