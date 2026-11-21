package ai.core.server.evaluation.store;

import ai.core.server.evaluation.domain.BaselineHistoryEntity;
import ai.core.server.evaluation.domain.EvalAttemptEntity;
import ai.core.server.evaluation.domain.EvalGradingEntity;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable run / trial state for evaluation execution.
 *
 * @author stephen
 */
public interface EvalRunStore {
    void insertRun(EvalRunEntity run);

    Optional<EvalRunEntity> getRun(String id);

    Optional<EvalRunEntity> findByRequestKey(String evaluationId, String actorId, String requestKey);

    List<EvalRunEntity> listRuns(String evaluationId, int offset, int limit);

    long countRuns(String evaluationId);

    List<EvalRunEntity> listActiveRuns(ZonedDateTime now);

    boolean markRunRunning(String runId, ZonedDateTime startedAt);

    boolean requestCancel(String runId, String actorId, ZonedDateTime now);

    boolean markRunBudgetExhausted(String runId);

    boolean finishRun(String runId, EvalRunEntity status, ZonedDateTime completedAt);

    boolean updateRunCounts(String runId, int started, int succeeded, int failed, int unmeasured);

    boolean refreshRunCounts(String runId);

    boolean updateRunReport(String runId, String reportJson, int succeeded, int failed, int unmeasured);

    void insertTrials(List<EvalTrialEntity> trials);

    Optional<EvalTrialEntity> getTrial(String id);

    List<EvalTrialEntity> listTrials(String runId, String status, String caseId, String taskResult, int offset, int limit);

    List<EvalTrialEntity> listAllTrials(String runId);

    long countTrials(String runId, String status, String caseId, String taskResult);

    List<EvalTrialEntity> findClaimable(String runId, int limit, ZonedDateTime now);

    List<EvalTrialEntity> listActiveTrials(String runId);

    List<EvalTrialEntity> findExpiredLeases(ZonedDateTime now, int limit);

    int countActiveTrials(String runId);

    int countTrialsByRun(String runId);

    int countFinalTrials(String runId);

    boolean claimTrial(String id, int generation, String owner, ZonedDateTime leaseUntil);

    boolean markTrialExecuting(String id, int generation, String agentRunId, String dispatchToken,
                               ZonedDateTime startedAt, ZonedDateTime leaseUntil);

    boolean attachAgentRun(String id, int generation, String agentRunId);

    boolean markTrialStatus(String id, int generation, String status);

    boolean completeTrial(EvalTrialEntity trial);

    boolean resetExpiredLease(String id, int generation, ZonedDateTime leaseUntil);

    boolean markPendingNotExecuted(String runId, ZonedDateTime completedAt);

    void insertAttempt(EvalAttemptEntity attempt);

    void updateAttempt(EvalAttemptEntity attempt);

    List<EvalAttemptEntity> listAttempts(String trialId);

    void insertGrading(EvalGradingEntity grading);

    Optional<EvalGradingEntity> findLatestGrading(String trialId);

    List<EvalGradingEntity> listGradings(String trialId);

    boolean chooseBaseline(String evaluationId, String baselineRunId, int expectedGeneration, int newGeneration,
                        BaselineHistoryEntity history);

    List<BaselineHistoryEntity> listBaselineHistory(String evaluationId);

    List<EvalRunEntity> findRunsForEvaluation(String evaluationId, List<String> runIds);
}
