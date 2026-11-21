package ai.core.server.evaluation.store;

import ai.core.server.evaluation.domain.BaselineHistoryEntity;
import ai.core.server.evaluation.domain.EvalAttemptEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalGradingEntity;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;

/**
 * @author stephen
 */
public final class EvalRunRowMapper {
    public static EvalRunEntity toRun(PgRunRow row) {
        var entity = JsonUtil.fromJson(EvalRunEntity.class, row.data);
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.actorId = row.actorId;
        entity.requestKey = row.requestKey;
        entity.requestHash = row.requestHash;
        entity.specId = row.specId;
        entity.snapshotId = row.snapshotId;
        entity.purpose = EvalEnums.parse(EvalEnums.Purpose.class, row.purpose);
        entity.experimentLabel = row.experimentLabel;
        entity.status = EvalEnums.parse(EvalEnums.RunStatus.class, row.status);
        entity.plannedTrials = row.plannedTrials;
        entity.startedTrials = row.startedTrials;
        entity.succeededTrials = row.succeededTrials;
        entity.failedTrials = row.failedTrials;
        entity.unmeasuredTrials = row.unmeasuredTrials;
        entity.cancelRequestedBy = row.cancelRequestedBy;
        entity.cancelRequestedAt = row.cancelRequestedAt;
        entity.deadlineAt = row.deadlineAt;
        entity.reportJson = row.reportJson;
        entity.error = row.error;
        entity.createdAt = row.createdAt;
        entity.startedAt = row.startedAt;
        entity.completedAt = row.completedAt;
        return entity;
    }

    public static EvalTrialEntity toTrial(PgTrialRow row) {
        var entity = JsonUtil.fromJson(EvalTrialEntity.class, row.data);
        entity.id = row.id;
        entity.runId = row.runId;
        entity.caseId = row.caseId;
        entity.caseHash = row.caseHash;
        entity.repetitionIndex = row.repetitionIndex;
        entity.split = row.split;
        entity.weight = row.weight;
        entity.status = EvalEnums.parse(EvalEnums.TrialStatus.class, row.status);
        entity.executionResult = EvalEnums.parse(EvalEnums.ExecutionResult.class, row.executionResult);
        entity.taskResult = EvalEnums.parse(EvalEnums.TaskResult.class, row.taskResult);
        entity.agentRunId = row.agentRunId;
        entity.owner = row.owner;
        entity.generation = row.generation;
        entity.leaseUntil = row.leaseUntil;
        entity.dispatchToken = row.dispatchToken;
        entity.actualOutput = row.actualOutput;
        entity.traceJson = row.traceJson;
        entity.artifactsJson = row.artifactsJson;
        entity.usageJson = row.usageJson;
        entity.gradingJson = row.gradingJson;
        entity.error = row.error;
        entity.stopRequestedAt = row.stopRequestedAt;
        entity.stopConfirmedAt = row.stopConfirmedAt;
        entity.startedAt = row.startedAt;
        entity.completedAt = row.completedAt;
        entity.createdAt = row.createdAt;
        return entity;
    }

    public static EvalGradingEntity toGrading(PgGradingRow row) {
        var entity = new EvalGradingEntity();
        entity.id = row.id;
        entity.trialId = row.trialId;
        entity.graderSuiteId = row.graderSuiteId;
        entity.outputHash = row.outputHash;
        entity.generation = row.generation;
        entity.status = EvalEnums.parse(EvalEnums.GradingStatus.class, row.status);
        entity.resultsJson = row.resultsJson;
        entity.error = row.error;
        entity.createdAt = row.createdAt;
        entity.completedAt = row.completedAt;
        return entity;
    }

    public static EvalAttemptEntity toAttempt(PgAttemptRow row) {
        var entity = new EvalAttemptEntity();
        entity.id = row.id;
        entity.trialId = row.trialId;
        entity.attemptNo = row.attemptNo;
        entity.dispatchToken = row.dispatchToken;
        entity.status = row.status;
        entity.agentRunId = row.agentRunId;
        entity.detail = row.detail;
        entity.createdAt = row.createdAt;
        entity.updatedAt = row.updatedAt;
        return entity;
    }

    public static BaselineHistoryEntity toBaseline(PgBaselineRow row) {
        var entity = new BaselineHistoryEntity();
        entity.id = row.id;
        entity.evaluationId = row.evaluationId;
        entity.baselineRunId = row.baselineRunId;
        entity.previousRunId = row.previousRunId;
        entity.generation = row.generation;
        entity.reason = row.reason;
        entity.actorId = row.actorId;
        entity.createdAt = row.createdAt;
        return entity;
    }

    public static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public static String jsonOrNull(String json) {
        return json == null ? null : JsonSanitizer.json(json);
    }

    private EvalRunRowMapper() {
    }
}
