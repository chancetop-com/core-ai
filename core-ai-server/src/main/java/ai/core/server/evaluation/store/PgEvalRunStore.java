package ai.core.server.evaluation.store;

import ai.core.server.evaluation.domain.BaselineHistoryEntity;
import ai.core.server.evaluation.domain.EvalAttemptEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalGradingEntity;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgEvalRunStore implements EvalRunStore {
    private static final String RUN_TABLE = "app.eval_runs";
    private static final String RUN_SELECT = "SELECT id, evaluation_id, actor_id, request_key, request_hash, spec_id, snapshot_id, "
        + "purpose, experiment_label, status, planned_trials, started_trials, succeeded_trials, failed_trials, "
        + "unmeasured_trials, cancel_requested_by, cancel_requested_at, deadline_at, report_json::text AS report_json, error, "
        + "created_at, started_at, completed_at, data::text AS data FROM " + RUN_TABLE;

    private static final String TRIAL_TABLE = "app.eval_trials";
    private static final String TRIAL_SELECT = "SELECT id, run_id, case_id, case_hash, repetition_index, split, weight, status, "
        + "execution_result, task_result, agent_run_id, owner, generation, lease_until, dispatch_token, actual_output, "
        + "trace_json::text AS trace_json, artifacts_json::text AS artifacts_json, usage_json::text AS usage_json, "
        + "grading_json::text AS grading_json, error, stop_requested_at, stop_confirmed_at, started_at, completed_at, "
        + "created_at, data::text AS data FROM " + TRIAL_TABLE;

    private static final String GRADING_TABLE = "app.eval_gradings";
    private static final String GRADING_SELECT = "SELECT id, trial_id, grader_suite_id, output_hash, generation, status, "
        + "results_json::text AS results_json, error, created_at, completed_at FROM " + GRADING_TABLE;

    private static final String ATTEMPT_TABLE = "app.eval_trial_attempts";
    private static final String ATTEMPT_SELECT = "SELECT id, trial_id, attempt_no, dispatch_token, status, agent_run_id, detail, "
        + "created_at, updated_at FROM " + ATTEMPT_TABLE;

    private static final String BASELINE_TABLE = "app.eval_baseline_history";
    private static final String BASELINE_SELECT = "SELECT id, evaluation_id, baseline_run_id, previous_run_id, generation, "
        + "reason, actor_id, created_at FROM " + BASELINE_TABLE;

    @Inject
    public Database database;

    @Override
    public void insertRun(EvalRunEntity run) {
        database.execute("INSERT INTO " + RUN_TABLE + " (id, evaluation_id, actor_id, request_key, request_hash, spec_id, "
                + "snapshot_id, purpose, experiment_label, status, planned_trials, started_trials, succeeded_trials, "
                + "failed_trials, unmeasured_trials, cancel_requested_by, cancel_requested_at, deadline_at, budget_json, "
                + "report_json, error, created_at, started_at, completed_at, data) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb)",
            run.id, run.evaluationId, run.actorId, run.requestKey, run.requestHash, run.specId, run.snapshotId,
            run.purpose.name(), run.experimentLabel, run.status.name(), run.plannedTrials, run.startedTrials,
            run.succeededTrials, run.failedTrials, run.unmeasuredTrials, run.cancelRequestedBy, run.cancelRequestedAt,
            run.deadlineAt, run.budgetJson == null ? null : JsonSanitizer.json(run.budgetJson),
            run.reportJson == null ? null : JsonSanitizer.json(run.reportJson), run.error, run.createdAt, run.startedAt,
            run.completedAt, JsonSanitizer.json(JsonUtil.toJson(run)));
    }

    @Override
    public Optional<EvalRunEntity> getRun(String id) {
        return database.selectOne(RUN_SELECT + " WHERE id = ?", PgRunRow.class, id).map(EvalRunRowMapper::toRun);
    }

    @Override
    public Optional<EvalRunEntity> findByRequestKey(String evaluationId, String actorId, String requestKey) {
        return database.selectOne(RUN_SELECT + " WHERE evaluation_id = ? AND actor_id = ? AND request_key = ?",
            PgRunRow.class, evaluationId, actorId, requestKey).map(EvalRunRowMapper::toRun);
    }

    @Override
    public List<EvalRunEntity> listRuns(String evaluationId, int offset, int limit) {
        return database.select(RUN_SELECT + " WHERE evaluation_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                PgRunRow.class, evaluationId, Math.clamp(limit, 1, 200), Math.max(offset, 0))
            .stream().map(EvalRunRowMapper::toRun).toList();
    }

    @Override
    public long countRuns(String evaluationId) {
        return database.selectOne("SELECT count(*) AS total FROM " + RUN_TABLE + " WHERE evaluation_id = ?",
            PgEvalCountRow.class, evaluationId).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<EvalRunEntity> listActiveRuns(ZonedDateTime now) {
        return database.select(RUN_SELECT + " WHERE status IN (?, ?, ?) AND completed_at IS NULL ORDER BY created_at",
                PgRunRow.class, EvalEnums.RunStatus.RUNNING.name(), EvalEnums.RunStatus.CANCELLING.name(),
                EvalEnums.RunStatus.BUDGET_EXHAUSTED.name())
            .stream().map(EvalRunRowMapper::toRun).toList();
    }

    @Override
    public boolean markRunRunning(String runId, ZonedDateTime startedAt) {
        return database.execute("UPDATE " + RUN_TABLE + " SET status = ?, started_at = COALESCE(started_at, ?) "
                + "WHERE id = ? AND status IN (?, ?)",
            EvalEnums.RunStatus.RUNNING.name(), startedAt, runId,
            EvalEnums.RunStatus.QUEUED.name(), EvalEnums.RunStatus.PREFLIGHT.name()) > 0;
    }

    @Override
    public boolean requestCancel(String runId, String actorId, ZonedDateTime now) {
        return database.execute("UPDATE " + RUN_TABLE + " SET status = ?, cancel_requested_by = COALESCE(cancel_requested_by, ?), "
                + "cancel_requested_at = COALESCE(cancel_requested_at, ?) WHERE id = ? AND status IN (?, ?, ?, ?)",
            EvalEnums.RunStatus.CANCELLING.name(), actorId, now, runId,
            EvalEnums.RunStatus.QUEUED.name(), EvalEnums.RunStatus.PREFLIGHT.name(),
            EvalEnums.RunStatus.RUNNING.name(), EvalEnums.RunStatus.FINALIZING.name()) > 0;
    }

    @Override
    public boolean markRunBudgetExhausted(String runId) {
        return database.execute("UPDATE " + RUN_TABLE + " SET status = ? WHERE id = ? AND status IN (?, ?, ?)",
            EvalEnums.RunStatus.BUDGET_EXHAUSTED.name(), runId, EvalEnums.RunStatus.QUEUED.name(),
            EvalEnums.RunStatus.PREFLIGHT.name(), EvalEnums.RunStatus.RUNNING.name()) > 0;
    }

    @Override
    public boolean finishRun(String runId, EvalRunEntity status, ZonedDateTime completedAt) {
        return database.execute("UPDATE " + RUN_TABLE + " SET status = ?, report_json = ?::jsonb, error = ?, "
                + "succeeded_trials = ?, failed_trials = ?, unmeasured_trials = ?, completed_at = ? "
                + "WHERE id = ? AND completed_at IS NULL",
            status.status.name(),
            status.reportJson == null ? null : JsonSanitizer.json(status.reportJson),
            status.error, status.succeededTrials, status.failedTrials, status.unmeasuredTrials, completedAt, runId) > 0;
    }

    @Override
    public boolean updateRunCounts(String runId, int started, int succeeded, int failed, int unmeasured) {
        return database.execute("UPDATE " + RUN_TABLE + " SET started_trials = ?, succeeded_trials = ?, "
                + "failed_trials = ?, unmeasured_trials = ? WHERE id = ?",
            started, succeeded, failed, unmeasured, runId) > 0;
    }

    @Override
    public boolean refreshRunCounts(String runId) {
        return database.execute("UPDATE " + RUN_TABLE + " r SET "
                + "started_trials = (SELECT count(*) FROM " + TRIAL_TABLE + " t WHERE t.run_id = r.id AND t.started_at IS NOT NULL), "
                + "succeeded_trials = (SELECT count(*) FROM " + TRIAL_TABLE + " t WHERE t.run_id = r.id AND t.task_result = ?), "
                + "failed_trials = (SELECT count(*) FROM " + TRIAL_TABLE + " t WHERE t.run_id = r.id AND t.task_result = ?), "
                + "unmeasured_trials = (SELECT count(*) FROM " + TRIAL_TABLE + " t WHERE t.run_id = r.id AND t.task_result = ?) "
                + "WHERE r.id = ?",
            EvalEnums.TaskResult.SUCCESS.name(), EvalEnums.TaskResult.FAILURE.name(),
            EvalEnums.TaskResult.UNMEASURED.name(), runId) > 0;
    }

    @Override
    public boolean updateRunReport(String runId, String reportJson, int succeeded, int failed, int unmeasured) {
        return database.execute("UPDATE " + RUN_TABLE + " SET report_json = ?::jsonb, succeeded_trials = ?, "
                + "failed_trials = ?, unmeasured_trials = ? WHERE id = ?",
            reportJson == null ? null : JsonSanitizer.json(reportJson), succeeded, failed, unmeasured, runId) > 0;
    }

    @Override
    public void insertTrials(List<EvalTrialEntity> trials) {
        for (var trial : trials) {
            database.execute("INSERT INTO " + TRIAL_TABLE + " (id, run_id, case_id, case_hash, repetition_index, split, weight, "
                    + "status, execution_result, task_result, agent_run_id, owner, generation, lease_until, dispatch_token, "
                    + "actual_output, trace_json, artifacts_json, usage_json, grading_json, error, stop_requested_at, "
                    + "stop_confirmed_at, started_at, completed_at, created_at, data) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                trial.id, trial.runId, trial.caseId, trial.caseHash, trial.repetitionIndex, trial.split, trial.weight,
                trial.status.name(), EvalRunRowMapper.enumName(trial.executionResult),
                EvalRunRowMapper.enumName(trial.taskResult), trial.agentRunId, trial.owner, trial.generation,
                trial.leaseUntil, trial.dispatchToken, trial.actualOutput, EvalRunRowMapper.jsonOrNull(trial.traceJson),
                EvalRunRowMapper.jsonOrNull(trial.artifactsJson), EvalRunRowMapper.jsonOrNull(trial.usageJson),
                EvalRunRowMapper.jsonOrNull(trial.gradingJson), trial.error, trial.stopRequestedAt, trial.stopConfirmedAt,
                trial.startedAt, trial.completedAt, trial.createdAt, JsonSanitizer.json(JsonUtil.toJson(trial)));
        }
    }

    @Override
    public Optional<EvalTrialEntity> getTrial(String id) {
        return database.selectOne(TRIAL_SELECT + " WHERE id = ?", PgTrialRow.class, id).map(EvalRunRowMapper::toTrial);
    }

    @Override
    public List<EvalTrialEntity> listTrials(String runId, String status, String caseId, String taskResult, int offset, int limit) {
        var sql = new StringBuilder(1024).append(TRIAL_SELECT).append(" WHERE run_id = ?");
        var params = new ArrayList<>();
        params.add(runId);
        addFilter(sql, params, "status", status);
        addFilter(sql, params, "case_id", caseId);
        addFilter(sql, params, "task_result", taskResult);
        sql.append(" ORDER BY case_id, repetition_index LIMIT ? OFFSET ?");
        params.add(Math.clamp(limit, 1, 1000));
        params.add(Math.max(offset, 0));
        return database.select(sql.toString(), PgTrialRow.class, params.toArray()).stream()
            .map(EvalRunRowMapper::toTrial).toList();
    }

    @Override
    public List<EvalTrialEntity> listAllTrials(String runId) {
        return database.select(TRIAL_SELECT + " WHERE run_id = ? ORDER BY case_id, repetition_index",
                PgTrialRow.class, runId).stream().map(EvalRunRowMapper::toTrial).toList();
    }

    @Override
    public long countTrials(String runId, String status, String caseId, String taskResult) {
        var sql = new StringBuilder(256).append("SELECT count(*) AS total FROM ").append(TRIAL_TABLE).append(" WHERE run_id = ?");
        var params = new ArrayList<>();
        params.add(runId);
        addFilter(sql, params, "status", status);
        addFilter(sql, params, "case_id", caseId);
        addFilter(sql, params, "task_result", taskResult);
        return database.selectOne(sql.toString(), PgEvalCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<EvalTrialEntity> findClaimable(String runId, int limit, ZonedDateTime now) {
        return database.select(TRIAL_SELECT + " WHERE run_id = ? AND status = ? AND run_id IN ("
                    + "SELECT id FROM " + RUN_TABLE + " WHERE id = ? AND status = ? AND deadline_at > ?) "
                    + "ORDER BY created_at, id LIMIT ?",
                PgTrialRow.class, runId, EvalEnums.TrialStatus.PENDING.name(),
                runId, EvalEnums.RunStatus.RUNNING.name(), now, Math.clamp(limit, 1, 100))
            .stream().map(EvalRunRowMapper::toTrial).toList();
    }

    @Override
    public List<EvalTrialEntity> listActiveTrials(String runId) {
        return database.select(TRIAL_SELECT + " WHERE run_id = ? AND status IN (?, ?, ?, ?, ?) ORDER BY created_at",
                PgTrialRow.class, runId,
                EvalEnums.TrialStatus.LEASED.name(), EvalEnums.TrialStatus.PREPARING.name(),
                EvalEnums.TrialStatus.EXECUTING.name(), EvalEnums.TrialStatus.EXECUTION_TERMINAL.name(),
                EvalEnums.TrialStatus.GRADING.name())
            .stream().map(EvalRunRowMapper::toTrial).toList();
    }

    @Override
    public List<EvalTrialEntity> findExpiredLeases(ZonedDateTime now, int limit) {
        return database.select(TRIAL_SELECT + " WHERE status IN (?, ?, ?, ?, ?) AND lease_until <= ? "
                + "ORDER BY lease_until LIMIT ?",
                PgTrialRow.class, EvalEnums.TrialStatus.LEASED.name(), EvalEnums.TrialStatus.PREPARING.name(),
                EvalEnums.TrialStatus.EXECUTING.name(), EvalEnums.TrialStatus.EXECUTION_TERMINAL.name(),
                EvalEnums.TrialStatus.GRADING.name(), now, Math.clamp(limit, 1, 200))
            .stream().map(EvalRunRowMapper::toTrial).toList();
    }

    @Override
    public int countActiveTrials(String runId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TRIAL_TABLE + " WHERE run_id = ? "
                + "AND status IN (?, ?, ?, ?, ?)", PgEvalCountRow.class, runId,
            EvalEnums.TrialStatus.LEASED.name(), EvalEnums.TrialStatus.PREPARING.name(),
            EvalEnums.TrialStatus.EXECUTING.name(), EvalEnums.TrialStatus.EXECUTION_TERMINAL.name(),
            EvalEnums.TrialStatus.GRADING.name()).map(row -> row.total.intValue()).orElse(0);
    }

    @Override
    public int countTrialsByRun(String runId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TRIAL_TABLE + " WHERE run_id = ?",
            PgEvalCountRow.class, runId).map(row -> row.total.intValue()).orElse(0);
    }

    @Override
    public int countFinalTrials(String runId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TRIAL_TABLE + " WHERE run_id = ? AND status = ?",
            PgEvalCountRow.class, runId, EvalEnums.TrialStatus.FINAL.name())
            .map(row -> row.total.intValue()).orElse(0);
    }

    @Override
    public boolean claimTrial(String id, int generation, String owner, ZonedDateTime leaseUntil) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ?, owner = ?, lease_until = ?, generation = generation + 1 "
                + "WHERE id = ? AND status = ? AND generation = ?",
            EvalEnums.TrialStatus.LEASED.name(), owner, leaseUntil, id,
            EvalEnums.TrialStatus.PENDING.name(), generation) > 0;
    }

    @Override
    public boolean markTrialExecuting(String id, int generation, String agentRunId, String dispatchToken,
                                      ZonedDateTime startedAt, ZonedDateTime leaseUntil) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ?, agent_run_id = ?, dispatch_token = ?, "
                + "started_at = COALESCE(started_at, ?), lease_until = ? WHERE id = ? AND generation = ? AND status <> ?",
            EvalEnums.TrialStatus.EXECUTING.name(), agentRunId, dispatchToken, startedAt, leaseUntil, id, generation,
            EvalEnums.TrialStatus.FINAL.name()) > 0;
    }

    @Override
    public boolean attachAgentRun(String id, int generation, String agentRunId) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET agent_run_id = ? WHERE id = ? AND generation = ? "
                + "AND status <> ?",
            agentRunId, id, generation, EvalEnums.TrialStatus.FINAL.name()) > 0;
    }

    @Override
    public boolean markTrialStatus(String id, int generation, String status) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ? WHERE id = ? AND generation = ? AND status <> ?",
            status, id, generation, EvalEnums.TrialStatus.FINAL.name()) > 0;
    }

    @Override
    public boolean completeTrial(EvalTrialEntity trial) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ?, execution_result = ?, task_result = ?, "
                + "agent_run_id = ?, actual_output = ?, trace_json = ?::jsonb, artifacts_json = ?::jsonb, usage_json = ?::jsonb, "
                + "grading_json = ?::jsonb, error = ?, stop_requested_at = ?, stop_confirmed_at = ?, completed_at = ? "
                + "WHERE id = ? AND generation = ? AND status <> ?",
            EvalEnums.TrialStatus.FINAL.name(), EvalRunRowMapper.enumName(trial.executionResult),
            EvalRunRowMapper.enumName(trial.taskResult), trial.agentRunId, trial.actualOutput,
            EvalRunRowMapper.jsonOrNull(trial.traceJson), EvalRunRowMapper.jsonOrNull(trial.artifactsJson),
            EvalRunRowMapper.jsonOrNull(trial.usageJson), EvalRunRowMapper.jsonOrNull(trial.gradingJson),
            trial.error, trial.stopRequestedAt, trial.stopConfirmedAt, trial.completedAt, trial.id, trial.generation,
            EvalEnums.TrialStatus.FINAL.name()) > 0;
    }

    @Override
    public boolean resetExpiredLease(String id, int generation, ZonedDateTime leaseUntil) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ?, owner = NULL, lease_until = ?, generation = generation + 1 "
                + "WHERE id = ? AND generation = ? AND status IN (?, ?)",
            EvalEnums.TrialStatus.PENDING.name(), leaseUntil, id, generation,
            EvalEnums.TrialStatus.LEASED.name(), EvalEnums.TrialStatus.PREPARING.name()) > 0;
    }

    @Override
    public boolean markPendingNotExecuted(String runId, ZonedDateTime completedAt) {
        return database.execute("UPDATE " + TRIAL_TABLE + " SET status = ?, execution_result = ?, task_result = ?, completed_at = ? "
                + "WHERE run_id = ? AND status = ?",
            EvalEnums.TrialStatus.FINAL.name(), EvalEnums.ExecutionResult.NOT_EXECUTED.name(),
            EvalEnums.TaskResult.UNMEASURED.name(), completedAt, runId, EvalEnums.TrialStatus.PENDING.name()) > 0;
    }

    @Override
    public void insertAttempt(EvalAttemptEntity attempt) {
        database.execute("INSERT INTO " + ATTEMPT_TABLE + " (id, trial_id, attempt_no, dispatch_token, status, agent_run_id, "
                + "detail, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            attempt.id, attempt.trialId, attempt.attemptNo, attempt.dispatchToken, attempt.status, attempt.agentRunId,
            attempt.detail, attempt.createdAt, attempt.updatedAt);
    }

    @Override
    public void updateAttempt(EvalAttemptEntity attempt) {
        database.execute("UPDATE " + ATTEMPT_TABLE + " SET status = ?, agent_run_id = ?, detail = ?, updated_at = ? WHERE id = ?",
            attempt.status, attempt.agentRunId, attempt.detail, attempt.updatedAt, attempt.id);
    }

    @Override
    public List<EvalAttemptEntity> listAttempts(String trialId) {
        return database.select(ATTEMPT_SELECT + " WHERE trial_id = ? ORDER BY attempt_no", PgAttemptRow.class, trialId)
            .stream().map(EvalRunRowMapper::toAttempt).toList();
    }

    @Override
    public void insertGrading(EvalGradingEntity grading) {
        database.execute("INSERT INTO " + GRADING_TABLE + " (id, trial_id, grader_suite_id, output_hash, generation, status, "
                + "results_json, error, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
            grading.id, grading.trialId, grading.graderSuiteId, grading.outputHash, grading.generation,
            grading.status.name(), JsonSanitizer.json(grading.resultsJson), grading.error, grading.createdAt,
            grading.completedAt);
    }

    @Override
    public Optional<EvalGradingEntity> findLatestGrading(String trialId) {
        return database.selectOne(GRADING_SELECT + " WHERE trial_id = ? ORDER BY created_at DESC LIMIT 1",
            PgGradingRow.class, trialId).map(EvalRunRowMapper::toGrading);
    }

    @Override
    public List<EvalGradingEntity> listGradings(String trialId) {
        return database.select(GRADING_SELECT + " WHERE trial_id = ? ORDER BY created_at", PgGradingRow.class, trialId)
            .stream().map(EvalRunRowMapper::toGrading).toList();
    }

    @Override
    public boolean chooseBaseline(String evaluationId, String baselineRunId, int expectedGeneration, int newGeneration,
                               BaselineHistoryEntity history) {
        try (var transaction = database.beginTransaction()) {
            var updated = database.execute("UPDATE app.eval_evaluations SET baseline_run_id = ?, baseline_generation = ?, "
                    + "updated_at = now() WHERE id = ? AND baseline_generation = ?",
                baselineRunId, newGeneration, evaluationId, expectedGeneration);
            if (updated == 0) {
                transaction.rollback();
                return false;
            }
            database.execute("INSERT INTO " + BASELINE_TABLE + " (id, evaluation_id, baseline_run_id, previous_run_id, "
                    + "generation, reason, actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                history.id, history.evaluationId, history.baselineRunId, history.previousRunId, history.generation,
                history.reason, history.actorId, history.createdAt);
            transaction.commit();
            return true;
        }
    }

    @Override
    public List<BaselineHistoryEntity> listBaselineHistory(String evaluationId) {
        return database.select(BASELINE_SELECT + " WHERE evaluation_id = ? ORDER BY created_at DESC",
                PgBaselineRow.class, evaluationId).stream().map(EvalRunRowMapper::toBaseline).toList();
    }

    @Override
    public List<EvalRunEntity> findRunsForEvaluation(String evaluationId, List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return List.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
        var params = new ArrayList<>();
        params.add(evaluationId);
        params.addAll(runIds);
        return database.select(RUN_SELECT + " WHERE evaluation_id = ? AND id IN (" + placeholders + ")",
                PgRunRow.class, params.toArray()).stream().map(EvalRunRowMapper::toRun).toList();
    }

    private void addFilter(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append(" = ?");
        params.add(value);
    }
}
