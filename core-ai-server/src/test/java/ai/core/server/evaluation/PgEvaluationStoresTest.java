package ai.core.server.evaluation;

import ai.core.server.evaluation.domain.BaselineHistoryEntity;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.server.evaluation.store.PgEvalRunStore;
import ai.core.server.evaluation.store.PgEvaluationStore;
import ai.core.server.evaluation.store.PgEvaluationRow;
import ai.core.server.evaluation.store.PgDatasetRevisionRow;
import ai.core.server.evaluation.store.PgEvalCaseRow;
import ai.core.server.evaluation.store.PgEvalCountRow;
import ai.core.server.evaluation.store.PgEvalNextRow;
import ai.core.server.evaluation.store.PgGraderSuiteRow;
import ai.core.server.evaluation.store.PgRunRow;
import ai.core.server.evaluation.store.PgSnapshotRow;
import ai.core.server.evaluation.store.PgSpecRow;
import ai.core.server.evaluation.store.PgTrialRow;
import ai.core.server.evaluation.store.PgGradingRow;
import ai.core.server.evaluation.store.PgAttemptRow;
import ai.core.server.evaluation.store.PgBaselineRow;
import ai.core.utils.JsonUtil;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgEvaluationStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("evaluation-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgEvaluationRow.class);
        database.view(PgDatasetRevisionRow.class);
        database.view(PgEvalCaseRow.class);
        database.view(PgEvalCountRow.class);
        database.view(PgEvalNextRow.class);
        database.view(PgGraderSuiteRow.class);
        database.view(PgSpecRow.class);
        database.view(PgSnapshotRow.class);
        database.view(PgRunRow.class);
        database.view(PgTrialRow.class);
        database.view(PgGradingRow.class);
        database.view(PgAttemptRow.class);
        database.view(PgBaselineRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_evaluations (id text PRIMARY KEY, owner_id text NOT NULL, "
                + "agent_id text NOT NULL, name text NOT NULL, description text, defaults_json jsonb, baseline_run_id text, "
                + "baseline_generation integer NOT NULL DEFAULT 0, archived_at timestamptz, created_at timestamptz NOT NULL, "
                + "updated_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_dataset_revisions (id text PRIMARY KEY, evaluation_id text NOT NULL, "
                + "revision integer NOT NULL, status text NOT NULL, manifest_json jsonb NOT NULL, content_hash text NOT NULL, "
                + "case_count integer NOT NULL, created_by text NOT NULL, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS eval_dataset_revisions_test_unique ON app.eval_dataset_revisions (evaluation_id, revision)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_cases (id text PRIMARY KEY, dataset_revision_id text NOT NULL, "
                + "case_id text NOT NULL, case_hash text NOT NULL, split text NOT NULL, weight double precision NOT NULL, body_json jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS eval_cases_test_unique ON app.eval_cases (dataset_revision_id, case_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_grader_suites (id text PRIMARY KEY, evaluation_id text NOT NULL, "
                + "revision integer NOT NULL, name text, fingerprint text NOT NULL, body_json jsonb NOT NULL, test_receipt_json jsonb, "
                + "created_by text NOT NULL, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_spec_revisions (id text PRIMARY KEY, evaluation_id text NOT NULL, "
                + "revision integer NOT NULL, dataset_revision_id text NOT NULL, grader_suite_id text NOT NULL, fingerprint text NOT NULL, "
                + "body_json jsonb NOT NULL, created_by text NOT NULL, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_candidate_snapshots (id text PRIMARY KEY, evaluation_id text NOT NULL, "
                + "agent_id text NOT NULL, source text NOT NULL, source_revision text, fingerprint text NOT NULL, snapshot_json jsonb NOT NULL, "
                + "created_by text NOT NULL, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_runs (id text PRIMARY KEY, evaluation_id text NOT NULL, actor_id text NOT NULL, "
                + "request_key text NOT NULL, request_hash text NOT NULL, spec_id text NOT NULL, snapshot_id text NOT NULL, purpose text NOT NULL, "
                + "experiment_label text, status text NOT NULL, planned_trials integer NOT NULL, started_trials integer NOT NULL DEFAULT 0, "
                + "succeeded_trials integer NOT NULL DEFAULT 0, failed_trials integer NOT NULL DEFAULT 0, unmeasured_trials integer NOT NULL DEFAULT 0, "
                + "cancel_requested_by text, cancel_requested_at timestamptz, deadline_at timestamptz NOT NULL, budget_json jsonb, report_json jsonb, "
                + "error text, created_at timestamptz NOT NULL, started_at timestamptz, completed_at timestamptz, data jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS eval_runs_test_request ON app.eval_runs (evaluation_id, actor_id, request_key)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_trials (id text PRIMARY KEY, run_id text NOT NULL, case_id text NOT NULL, "
                + "case_hash text NOT NULL, repetition_index integer NOT NULL, split text NOT NULL, weight double precision NOT NULL, status text NOT NULL, "
                + "execution_result text, task_result text, agent_run_id text, owner text, generation integer NOT NULL DEFAULT 0, lease_until timestamptz, "
                + "dispatch_token text, actual_output text, trace_json jsonb, artifacts_json jsonb, usage_json jsonb, grading_json jsonb, error text, "
                + "stop_requested_at timestamptz, stop_confirmed_at timestamptz, started_at timestamptz, completed_at timestamptz, created_at timestamptz NOT NULL, data jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS eval_trials_test_unique ON app.eval_trials (run_id, case_id, repetition_index)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_trial_attempts (id text PRIMARY KEY, trial_id text NOT NULL, attempt_no integer NOT NULL, "
                + "dispatch_token text, status text NOT NULL, agent_run_id text, detail text, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_gradings (id text PRIMARY KEY, trial_id text NOT NULL, grader_suite_id text NOT NULL, "
                + "output_hash text NOT NULL, generation integer NOT NULL, status text NOT NULL, results_json jsonb NOT NULL, error text, "
                + "created_at timestamptz NOT NULL, completed_at timestamptz)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.eval_baseline_history (id text PRIMARY KEY, evaluation_id text NOT NULL, "
                + "baseline_run_id text, previous_run_id text, generation integer NOT NULL, reason text, actor_id text NOT NULL, created_at timestamptz NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize evaluation test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        for (var table : List.of("app.eval_baseline_history", "app.eval_gradings", "app.eval_trial_attempts",
            "app.eval_trials", "app.eval_runs", "app.eval_candidate_snapshots", "app.eval_spec_revisions",
            "app.eval_grader_suites", "app.eval_cases", "app.eval_dataset_revisions", "app.eval_evaluations")) {
            DATABASE.execute("DELETE FROM " + table);
        }
    }

    @Test
    void catalogRevisionsAndSnapshotsRoundTrip() {
        var store = new PgEvaluationStore();
        store.database = DATABASE;
        var evaluation = evaluation("eval-1");
        store.insertEvaluation(evaluation);
        assertEquals("name", store.getEvaluation("eval-1").orElseThrow().name);
        assertEquals(1, store.listEvaluations("owner", false, null, 0, 20).size());
        assertEquals(1, store.countEvaluations("owner", false, null));

        var dataset = dataset("data-1", "eval-1", 1);
        store.insertDatasetRevision(dataset);
        store.insertCases(List.of(evalCase("case-1", "data-1", "c1", "DEV", 1.0)));
        assertEquals(1, store.countCases("data-1", "DEV"));
        assertEquals(1, store.listCases("data-1", "DEV", 0, 10).size());
        assertEquals(2, store.nextDatasetRevision("eval-1"));

        var suite = suite("suite-1", "eval-1", 1);
        store.insertGraderSuite(suite);
        assertEquals(1, store.listGraderSuites("eval-1").size());

        var spec = spec("spec-1", "eval-1", 1, "data-1", "suite-1");
        store.insertSpec(spec);
        assertEquals(1, store.listSpecs("eval-1").size());
        assertEquals("spec-1", store.findSpecByFingerprint("eval-1", "fingerprint").orElseThrow().id);

        var snapshot = snapshot("snap-1", "eval-1");
        store.insertSnapshot(snapshot);
        assertEquals("snap-1", store.getSnapshot("snap-1").orElseThrow().id);

        assertTrue(store.archiveEvaluation("eval-1"));
        assertNotNull(store.getEvaluation("eval-1").orElseThrow().archivedAt);
    }

    @Test
    void runTrialClaimAndBaselineCas() {
        var store = new PgEvaluationStore();
        store.database = DATABASE;
        store.insertEvaluation(evaluation("eval-1"));
        var runs = new PgEvalRunStore();
        runs.database = DATABASE;
        var now = ZonedDateTime.now();
        var run = run("run-1", "eval-1", now);
        runs.insertRun(run);
        runs.insertTrials(List.of(trial("trial-1", "run-1", "c1", 0), trial("trial-2", "run-1", "c1", 1)));

        var claimable = runs.findClaimable("run-1", 10, now);
        assertEquals(2, claimable.size());
        assertTrue(runs.claimTrial("trial-1", 0, "worker", now.plusMinutes(1)));
        assertFalse(runs.claimTrial("trial-1", 0, "other", now.plusMinutes(1)));
        assertEquals(1, runs.countActiveTrials("run-1"));
        assertEquals(1, runs.getTrial("trial-1").orElseThrow().generation);

        assertTrue(runs.markTrialExecuting("trial-1", 1, null, "dispatch", now, now.plusMinutes(20)));
        assertTrue(runs.attachAgentRun("trial-1", 1, "agent-run-1"));
        var trial = runs.getTrial("trial-1").orElseThrow();
        trial.executionResult = EvalEnums.ExecutionResult.SUCCEEDED;
        trial.taskResult = EvalEnums.TaskResult.SUCCESS;
        trial.actualOutput = "4";
        trial.usageJson = "{\"inputTokens\":3,\"outputTokens\":1}";
        trial.completedAt = now.plusSeconds(2);
        assertTrue(runs.completeTrial(trial));
        assertTrue(runs.refreshRunCounts("run-1"));
        assertEquals(1, runs.getRun("run-1").orElseThrow().succeededTrials);
        var usage = JsonUtil.toMap(runs.getTrial("trial-1").orElseThrow().usageJson);
        assertEquals(3, ((Number) usage.get("inputTokens")).intValue());
        assertEquals(1, ((Number) usage.get("outputTokens")).intValue());

        var history = new BaselineHistoryEntity();
        history.id = "h1";
        history.evaluationId = "eval-1";
        history.baselineRunId = "run-1";
        history.generation = 1;
        history.actorId = "owner";
        history.createdAt = now;
        assertTrue(runs.chooseBaseline("eval-1", "run-1", 0, 1, history));
        assertFalse(runs.chooseBaseline("eval-1", "run-1", 0, 1, history));
        assertEquals(1, runs.listBaselineHistory("eval-1").size());
    }

    private EvaluationEntity evaluation(String id) {
        var entity = new EvaluationEntity();
        entity.id = id;
        entity.ownerId = "owner";
        entity.agentId = "agent-1";
        entity.name = "name";
        entity.baselineGeneration = 0;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    private DatasetRevisionEntity dataset(String id, String evaluationId, int revision) {
        var entity = new DatasetRevisionEntity();
        entity.id = id;
        entity.evaluationId = evaluationId;
        entity.revision = revision;
        entity.status = "READY";
        entity.manifestJson = "{\"case_count\":1}";
        entity.contentHash = "hash";
        entity.caseCount = 1;
        entity.createdBy = "owner";
        entity.createdAt = ZonedDateTime.now();
        return entity;
    }

    private EvalCaseEntity evalCase(String id, String datasetId, String caseId, String split, double weight) {
        var entity = new EvalCaseEntity();
        entity.id = id;
        entity.datasetRevisionId = datasetId;
        entity.caseId = caseId;
        entity.caseHash = "case-hash";
        entity.split = split;
        entity.weight = weight;
        entity.bodyJson = "{\"caseId\":\"c1\"}";
        return entity;
    }

    private GraderSuiteEntity suite(String id, String evaluationId, int revision) {
        var entity = new GraderSuiteEntity();
        entity.id = id;
        entity.evaluationId = evaluationId;
        entity.revision = revision;
        entity.name = "suite";
        entity.fingerprint = "fingerprint";
        entity.bodyJson = "{\"graders\":[]}";
        entity.createdBy = "owner";
        entity.createdAt = ZonedDateTime.now();
        return entity;
    }

    private EvalSpecEntity spec(String id, String evaluationId, int revision, String datasetId, String suiteId) {
        var entity = new EvalSpecEntity();
        entity.id = id;
        entity.evaluationId = evaluationId;
        entity.revision = revision;
        entity.datasetRevisionId = datasetId;
        entity.graderSuiteId = suiteId;
        entity.fingerprint = "fingerprint";
        entity.bodyJson = "{\"repetitions\":1}";
        entity.createdBy = "owner";
        entity.createdAt = ZonedDateTime.now();
        return entity;
    }

    private ai.core.server.evaluation.domain.CandidateSnapshotEntity snapshot(String id, String evaluationId) {
        var entity = new ai.core.server.evaluation.domain.CandidateSnapshotEntity();
        entity.id = id;
        entity.evaluationId = evaluationId;
        entity.agentId = "agent-1";
        entity.source = "PUBLISHED";
        entity.fingerprint = "snapshot-fingerprint";
        entity.snapshotJson = "{\"agentId\":\"agent-1\"}";
        entity.createdBy = "owner";
        entity.createdAt = ZonedDateTime.now();
        return entity;
    }

    private EvalRunEntity run(String id, String evaluationId, ZonedDateTime now) {
        var entity = new EvalRunEntity();
        entity.id = id;
        entity.evaluationId = evaluationId;
        entity.actorId = "owner";
        entity.requestKey = "key";
        entity.requestHash = "hash";
        entity.specId = "spec-1";
        entity.snapshotId = "snap-1";
        entity.purpose = EvalEnums.Purpose.FORMAL;
        entity.status = EvalEnums.RunStatus.RUNNING;
        entity.plannedTrials = 2;
        entity.startedTrials = 0;
        entity.succeededTrials = 0;
        entity.failedTrials = 0;
        entity.unmeasuredTrials = 0;
        entity.deadlineAt = now.plusHours(1);
        entity.createdAt = now;
        entity.startedAt = now;
        return entity;
    }

    private EvalTrialEntity trial(String id, String runId, String caseId, int repetition) {
        var entity = new EvalTrialEntity();
        entity.id = id;
        entity.runId = runId;
        entity.caseId = caseId;
        entity.caseHash = "case-hash";
        entity.repetitionIndex = repetition;
        entity.split = "DEV";
        entity.weight = 1.0;
        entity.status = EvalEnums.TrialStatus.PENDING;
        entity.generation = 0;
        entity.createdAt = ZonedDateTime.now();
        return entity;
    }
}
