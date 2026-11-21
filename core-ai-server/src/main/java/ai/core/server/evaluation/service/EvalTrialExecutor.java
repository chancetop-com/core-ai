package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.EvalAttemptEntity;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalGradingEntity;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.grading.EvalGraderEngine;
import ai.core.server.evaluation.store.EvalRunStore;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes one leased trial: native agent run, immutable output capture, deterministic grading and
 * task-result classification. A lease owner must stop after a lost generation.
 *
 * @author stephen
 */
public class EvalTrialExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvalTrialExecutor.class);

    @Inject EvalRunStore runStore;
    @Inject EvaluationStore evaluationStore;
    @Inject EvalAgentRunGateway gateway;
    @Inject EvalGraderEngine graderEngine;
    @Inject EvalRunService runService;

    public void execute(String trialId, String owner) {
        var trial = runStore.getTrial(trialId).orElse(null);
        if (trial == null || trial.status != EvalEnums.TrialStatus.LEASED || !owner.equals(trial.owner)) return;
        var run = runStore.getRun(trial.runId).orElse(null);
        if (run == null) return;
        var attempt = startAttempt(trial);
        try {
            if (!executable(run)) {
                finishWithoutExecution(trial, attempt, run);
                return;
            }
            var prepared = prepare(trial, run);
            if (prepared == null) return;
            var outcome = gateway.await(prepared.agentRunId(), prepared.deadline());
            complete(trial, prepared.context(), outcome, attempt);
        } catch (RuntimeException e) {
            fail(trial, attempt, e);
        }
    }

    private boolean executable(EvalRunEntity run) {
        return run.status == EvalEnums.RunStatus.RUNNING && !ZonedDateTime.now().isAfter(run.deadlineAt);
    }

    private PreparedTrial prepare(EvalTrialEntity trial, EvalRunEntity run) {
        if (!runStore.markTrialStatus(trial.id, trial.generation, EvalEnums.TrialStatus.PREPARING.name())) return null;
        var context = loadContext(run, trial.caseId);
        var startedAt = ZonedDateTime.now();
        var deadline = min(run.deadlineAt, startedAt.plusSeconds(context.spec().trialDeadlineSeconds));
        var dispatchToken = UUID.randomUUID().toString();
        if (!runStore.markTrialExecuting(trial.id, trial.generation, null, dispatchToken,
            startedAt, deadline.plusSeconds(60))) return null;
        var variables = context.caseBody().input == null ? null : context.caseBody().input.variables;
        var input = context.caseBody().input == null ? "" : context.caseBody().input.text;
        var agentRunId = gateway.start(context.snapshot(), run.actorId, input, variables,
            context.spec().trialMaxTurns, context.spec().trialDeadlineSeconds);
        trial.agentRunId = agentRunId;
        if (!runStore.attachAgentRun(trial.id, trial.generation, agentRunId)) {
            gateway.cancel(agentRunId);
            return null;
        }
        return new PreparedTrial(context, agentRunId, deadline);
    }

    private TrialContext loadContext(EvalRunEntity run, String caseId) {
        var specEntity = evaluationStore.getSpecById(run.specId).orElseThrow();
        var spec = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, specEntity.bodyJson);
        var snapshotEntity = evaluationStore.getSnapshot(run.snapshotId).orElseThrow();
        var snapshot = JsonUtil.fromJson(EvalBodies.CandidateSnapshotBody.class, snapshotEntity.snapshotJson);
        var evalCase = findCase(spec, caseId);
        var caseBody = JsonUtil.fromJson(EvalBodies.CaseBody.class, evalCase.bodyJson);
        var suite = evaluationStore.getGraderSuiteById(spec.graderSuiteId).orElseThrow();
        var suiteBody = JsonUtil.fromJson(EvalBodies.GraderSuiteBody.class, suite.bodyJson);
        return new TrialContext(spec, snapshot, caseBody, suiteBody, spec.graderSuiteId);
    }

    private void complete(EvalTrialEntity trial, TrialContext context, EvalAgentRunGateway.Outcome outcome,
                          EvalAttemptEntity attempt) {
        trial.actualOutput = outcome.output();
        trial.usageJson = outcome.usageJson();
        trial.executionResult = outcome.result();
        trial.error = truncate(outcome.error(), 8000);
        trial.stopConfirmedAt = outcome.result() == EvalEnums.ExecutionResult.TIMEOUT
            || outcome.result() == EvalEnums.ExecutionResult.CANCELLED ? ZonedDateTime.now() : null;
        runStore.markTrialStatus(trial.id, trial.generation, EvalEnums.TrialStatus.GRADING.name());
        var bundle = grade(context.suiteBody(), context.caseBody(), trial, context.graderSuiteId());
        trial.gradingJson = JsonUtil.toJson(bundle);
        trial.taskResult = taskResult(trial.executionResult, bundle);
        trial.status = EvalEnums.TrialStatus.FINAL;
        trial.completedAt = ZonedDateTime.now();
        if (!runStore.completeTrial(trial)) {
            attempt.status = "STALE";
            runStore.updateAttempt(attempt);
            return;
        }
        attempt.status = "COMPLETED";
        attempt.detail = trial.taskResult == null ? null : trial.taskResult.name();
        runStore.updateAttempt(attempt);
        runStore.refreshRunCounts(trial.runId);
        runService.finalizeIfDone(trial.runId);
    }

    private void fail(EvalTrialEntity trial, EvalAttemptEntity attempt, RuntimeException e) {
        LOGGER.error("evaluation trial failed, trialId={}", trial.id, e);
        trial.status = EvalEnums.TrialStatus.FINAL;
        trial.executionResult = EvalEnums.ExecutionResult.PLATFORM_ERROR;
        trial.taskResult = EvalEnums.TaskResult.UNMEASURED;
        trial.error = truncate(e.getMessage(), 8000);
        trial.completedAt = ZonedDateTime.now();
        runStore.completeTrial(trial);
        attempt.status = "ERROR";
        attempt.detail = truncate(e.getMessage(), 2000);
        runStore.updateAttempt(attempt);
        runStore.refreshRunCounts(trial.runId);
        runService.finalizeIfDone(trial.runId);
    }

    private void finishWithoutExecution(EvalTrialEntity trial, EvalAttemptEntity attempt, EvalRunEntity run) {
        trial.status = EvalEnums.TrialStatus.FINAL;
        trial.executionResult = run.status == EvalEnums.RunStatus.BUDGET_EXHAUSTED
            ? EvalEnums.ExecutionResult.NOT_EXECUTED : EvalEnums.ExecutionResult.CANCELLED;
        trial.taskResult = EvalEnums.TaskResult.UNMEASURED;
        trial.completedAt = ZonedDateTime.now();
        runStore.completeTrial(trial);
        attempt.status = "NOT_EXECUTED";
        runStore.updateAttempt(attempt);
        runStore.refreshRunCounts(trial.runId);
        runService.finalizeIfDone(trial.runId);
    }

    private EvalCaseEntity findCase(EvalBodies.EvalSpecBody spec, String caseId) {
        var cases = evaluationStore.listCasesByIds(spec.datasetRevisionId, List.of(caseId));
        if (cases.isEmpty()) throw new EvalValidationException("caseId", "case disappeared from the dataset revision");
        return cases.get(0);
    }

    private EvalBodies.GradingBundle grade(EvalBodies.GraderSuiteBody suite, EvalBodies.CaseBody caseBody,
                                           EvalTrialEntity trial, String graderSuiteId) {
        var bundle = graderEngine.grade(suite, caseBody, trial.actualOutput, trial.executionResult);
        if (trial.executionResult != EvalEnums.ExecutionResult.SUCCEEDED) {
            var applicable = graderEngine.splitApplicable(suite, caseBody).applicable;
            bundle.results = new ArrayList<>(applicable.size());
            for (var grader : applicable) {
                var result = new EvalBodies.GraderResult();
                result.graderId = grader.id;
                result.required = Boolean.TRUE.equals(grader.required);
                result.status = EvalEnums.GradingStatus.NOT_RUN_TARGET_FAILED.name();
                result.feedback = "target execution did not succeed: " + trial.executionResult;
                bundle.results.add(result);
            }
        }
        var grading = new EvalGradingEntity();
        grading.id = UUID.randomUUID().toString();
        grading.trialId = trial.id;
        grading.graderSuiteId = graderSuiteId;
        grading.outputHash = EvalHashing.sha256Hex(trial.actualOutput == null ? "" : trial.actualOutput);
        grading.generation = trial.generation;
        grading.status = EvalEnums.GradingStatus.COMPLETED;
        grading.resultsJson = JsonUtil.toJson(bundle);
        grading.createdAt = ZonedDateTime.now();
        grading.completedAt = grading.createdAt;
        runStore.insertGrading(grading);
        return bundle;
    }

    private EvalEnums.TaskResult taskResult(EvalEnums.ExecutionResult executionResult, EvalBodies.GradingBundle bundle) {
        if (executionResult == EvalEnums.ExecutionResult.AGENT_FAILED || executionResult == EvalEnums.ExecutionResult.TIMEOUT) {
            return EvalEnums.TaskResult.FAILURE;
        }
        if (executionResult != EvalEnums.ExecutionResult.SUCCEEDED) return EvalEnums.TaskResult.UNMEASURED;
        boolean anyRequired = false;
        boolean anyFail = false;
        boolean allPass = true;
        if (bundle == null || bundle.results == null) return EvalEnums.TaskResult.UNMEASURED;
        for (var result : bundle.results) {
            if (!Boolean.TRUE.equals(result.required)) continue;
            anyRequired = true;
            if (!EvalEnums.GradingStatus.COMPLETED.name().equals(result.status)) {
                allPass = false;
                continue;
            }
            if (EvalEnums.Verdict.FAIL.name().equals(result.verdict)) {
                anyFail = true;
            } else if (!EvalEnums.Verdict.PASS.name().equals(result.verdict)) {
                allPass = false;
            }
        }
        if (!anyRequired) return EvalEnums.TaskResult.UNMEASURED;
        if (anyFail) return EvalEnums.TaskResult.FAILURE;
        return allPass ? EvalEnums.TaskResult.SUCCESS : EvalEnums.TaskResult.UNMEASURED;
    }

    private EvalAttemptEntity startAttempt(EvalTrialEntity trial) {
        var existing = runStore.listAttempts(trial.id);
        var attempt = new EvalAttemptEntity();
        attempt.id = UUID.randomUUID().toString();
        attempt.trialId = trial.id;
        attempt.attemptNo = existing.size() + 1;
        attempt.dispatchToken = trial.dispatchToken;
        attempt.status = "LEASED";
        attempt.createdAt = ZonedDateTime.now();
        attempt.updatedAt = attempt.createdAt;
        runStore.insertAttempt(attempt);
        return attempt;
    }

    private ZonedDateTime min(ZonedDateTime first, ZonedDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record TrialContext(EvalBodies.EvalSpecBody spec, EvalBodies.CandidateSnapshotBody snapshot,
                                EvalBodies.CaseBody caseBody, EvalBodies.GraderSuiteBody suiteBody, String graderSuiteId) {
    }

    private record PreparedTrial(TrialContext context, String agentRunId, ZonedDateTime deadline) {
    }
}
