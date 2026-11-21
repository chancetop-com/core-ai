package ai.core.server.evaluation.service;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.store.EvalRunStore;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Durable trial scheduler. Claims per-run capacity, recovers expired leases without replaying an
 * already-dispatched agent run, and stops issuing work when the run deadline is exhausted.
 *
 * @author stephen
 */
public class EvalSchedulerJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvalSchedulerJob.class);
    private static final int WORKERS = 8;
    private static final int QUEUE_BACKSTOP = 64;

    private final String workerId = "eval-worker-" + UUID.randomUUID();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_BACKSTOP), Executors.defaultThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    @Inject EvalRunStore runStore;
    @Inject EvaluationStore evaluationStore;
    @Inject EvalTrialExecutor trialExecutor;
    @Inject EvalRunService runService;
    @Inject EvalAgentRunGateway gateway;

    @Override
    public void execute(JobContext context) {
        var now = ZonedDateTime.now();
        recoverExpiredLeases(now);
        for (var run : runStore.listActiveRuns(now)) {
            try {
                if (run.status == EvalEnums.RunStatus.CANCELLING) {
                    runStore.markPendingNotExecuted(run.id, now);
                    for (var trial : runStore.listActiveTrials(run.id)) gateway.cancel(trial.agentRunId);
                    runService.finalizeIfDone(run.id);
                    continue;
                }
                if (run.status == EvalEnums.RunStatus.BUDGET_EXHAUSTED) {
                    runStore.markPendingNotExecuted(run.id, now);
                    for (var trial : runStore.listActiveTrials(run.id)) gateway.cancel(trial.agentRunId);
                    runService.finalizeIfDone(run.id);
                    continue;
                }
                if (run.deadlineAt != null && !now.isBefore(run.deadlineAt)) {
                    if (runStore.markRunBudgetExhausted(run.id)) {
                        runStore.markPendingNotExecuted(run.id, now);
                        for (var trial : runStore.listActiveTrials(run.id)) gateway.cancel(trial.agentRunId);
                    }
                    runService.finalizeIfDone(run.id);
                    continue;
                }
                if (run.status != EvalEnums.RunStatus.RUNNING) continue;
                var specEntity = evaluationStore.getSpecById(run.specId).orElse(null);
                if (specEntity == null) continue;
                var spec = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, specEntity.bodyJson);
                var capacity = spec.agentConcurrency - runStore.countActiveTrials(run.id);
                if (capacity <= 0) continue;
                for (var trial : runStore.findClaimable(run.id, Math.min(capacity, 20), now)) {
                    if (!runStore.claimTrial(trial.id, trial.generation, workerId, now.plusSeconds(120))) continue;
                    if (!submit(trial, now)) break;
                }
            } catch (RuntimeException e) {
                LOGGER.error("evaluation scheduler failed for run, runId={}", run.id, e);
            }
        }
    }

    private boolean submit(EvalTrialEntity trial, ZonedDateTime now) {
        try {
            executor.submit(() -> trialExecutor.execute(trial.id, workerId));
            return true;
        } catch (RejectedExecutionException e) {
            runStore.resetExpiredLease(trial.id, trial.generation + 1, now);
            return false;
        }
    }

    private void recoverExpiredLeases(ZonedDateTime now) {
        for (var trial : runStore.findExpiredLeases(now, 100)) {
            try {
                if (trial.status == EvalEnums.TrialStatus.LEASED || trial.status == EvalEnums.TrialStatus.PREPARING) {
                    runStore.resetExpiredLease(trial.id, trial.generation, now);
                    continue;
                }
                gateway.cancel(trial.agentRunId);
                trial.status = EvalEnums.TrialStatus.FINAL;
                trial.executionResult = EvalEnums.ExecutionResult.OUTCOME_UNKNOWN;
                trial.taskResult = EvalEnums.TaskResult.UNMEASURED;
                trial.error = "worker lease expired; runtime termination was not confirmed";
                trial.stopRequestedAt = now;
                trial.completedAt = now;
                if (runStore.completeTrial(trial)) {
                    runStore.refreshRunCounts(trial.runId);
                    runService.finalizeIfDone(trial.runId);
                }
            } catch (RuntimeException e) {
                LOGGER.error("evaluation lease recovery failed, trialId={}", trial.id, e);
            }
        }
    }
}
