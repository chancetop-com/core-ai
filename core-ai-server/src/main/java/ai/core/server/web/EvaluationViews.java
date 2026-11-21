package ai.core.server.web;

import ai.core.api.server.evaluation.response.CaseDeltaView;
import ai.core.api.server.evaluation.response.CaseReportView;
import ai.core.api.server.evaluation.response.ComparisonView;
import ai.core.api.server.evaluation.response.DatasetRevisionView;
import ai.core.api.server.evaluation.response.EvalCaseView;
import ai.core.api.server.evaluation.response.EvalSpecView;
import ai.core.api.server.evaluation.response.EvaluationView;
import ai.core.api.server.evaluation.response.GraderReportView;
import ai.core.api.server.evaluation.response.GraderSuiteView;
import ai.core.api.server.evaluation.response.RunReportView;
import ai.core.api.server.evaluation.response.RunView;
import ai.core.api.server.evaluation.response.TrialDetailView;
import ai.core.api.server.evaluation.response.TrialSummaryView;
import ai.core.api.server.evaluation.response.TrialView;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps evaluation entities and report bodies to their public DTOs.
 *
 * @author stephen
 */
public final class EvaluationViews {
    public static EvaluationView evaluation(EvaluationEntity entity) {
        var view = new EvaluationView();
        view.id = entity.id;
        view.ownerId = entity.ownerId;
        view.agentId = entity.agentId;
        view.name = entity.name;
        view.description = entity.description;
        view.baselineRunId = entity.baselineRunId;
        view.baselineGeneration = entity.baselineGeneration;
        view.archivedAt = entity.archivedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    public static DatasetRevisionView dataset(DatasetRevisionEntity entity) {
        var view = new DatasetRevisionView();
        view.id = entity.id;
        view.evaluationId = entity.evaluationId;
        view.revision = entity.revision;
        view.status = entity.status;
        view.caseCount = entity.caseCount;
        view.contentHash = entity.contentHash;
        view.manifestJson = entity.manifestJson;
        view.createdBy = entity.createdBy;
        view.createdAt = entity.createdAt;
        return view;
    }

    public static EvalCaseView evalCase(EvalCaseEntity entity, boolean oracle) {
        var body = JsonUtil.fromJson(EvalBodies.CaseBody.class, entity.bodyJson);
        var view = new EvalCaseView();
        view.caseId = entity.caseId;
        view.split = entity.split;
        view.weight = entity.weight;
        view.inputText = body.input == null ? null : body.input.text;
        view.graderRefsJson = JsonUtil.toJson(body.graderRefs == null ? List.of() : body.graderRefs);
        view.tagsJson = body.metadata == null ? null : JsonUtil.toJson(body.metadata.tags);
        view.environmentJson = body.environment == null ? null : JsonUtil.toJson(body.environment);
        if (oracle) view.referenceJson = body.reference == null ? null : JsonUtil.toJson(body.reference);
        return view;
    }

    public static GraderSuiteView graderSuite(GraderSuiteEntity entity) {
        var view = new GraderSuiteView();
        view.id = entity.id;
        view.revision = entity.revision;
        view.name = entity.name;
        view.fingerprint = entity.fingerprint;
        view.bodyJson = entity.bodyJson;
        view.testReceiptJson = entity.testReceiptJson;
        view.createdAt = entity.createdAt;
        return view;
    }

    public static EvalSpecView spec(EvalSpecEntity entity) {
        var body = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, entity.bodyJson);
        var view = new EvalSpecView();
        view.id = entity.id;
        view.revision = entity.revision;
        view.datasetRevisionId = entity.datasetRevisionId;
        view.datasetFingerprint = body.datasetFingerprint;
        view.graderSuiteId = entity.graderSuiteId;
        view.fingerprint = entity.fingerprint;
        view.profile = body.profile;
        view.repetitions = body.repetitions;
        view.agentConcurrency = body.agentConcurrency;
        view.trialDeadlineSeconds = body.trialDeadlineSeconds;
        view.trialMaxTurns = body.trialMaxTurns;
        view.runDeadlineSeconds = body.runDeadlineSeconds;
        view.defaultClock = body.defaultEvaluationClock;
        view.split = body.split;
        view.caseCount = body.caseIds == null ? null : body.caseIds.size();
        view.plannedTrials = body.caseIds == null || body.repetitions == null ? null : body.caseIds.size() * body.repetitions;
        view.bodyJson = entity.bodyJson;
        view.createdAt = entity.createdAt;
        return view;
    }

    public static RunView run(EvalRunEntity entity) {
        var view = new RunView();
        view.id = entity.id;
        view.evaluationId = entity.evaluationId;
        view.actorId = entity.actorId;
        view.specId = entity.specId;
        view.snapshotId = entity.snapshotId;
        view.purpose = entity.purpose == null ? null : entity.purpose.name();
        view.experimentLabel = entity.experimentLabel;
        view.status = entity.status == null ? null : entity.status.name();
        view.plannedTrials = entity.plannedTrials;
        view.startedTrials = entity.startedTrials;
        view.succeededTrials = entity.succeededTrials;
        view.failedTrials = entity.failedTrials;
        view.unmeasuredTrials = entity.unmeasuredTrials;
        view.cancelRequestedBy = entity.cancelRequestedBy;
        view.deadlineAt = entity.deadlineAt;
        view.createdAt = entity.createdAt;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        view.error = entity.error;
        view.requestKey = entity.requestKey;
        return view;
    }

    public static TrialView trial(EvalTrialEntity entity) {
        var view = new TrialView();
        copyTrial(entity, view);
        return view;
    }

    public static TrialDetailView trialDetail(EvalTrialEntity entity, EvalCaseEntity evalCase, boolean oracle) {
        var view = new TrialDetailView();
        view.id = entity.id;
        view.runId = entity.runId;
        view.caseId = entity.caseId;
        view.repetitionIndex = entity.repetitionIndex;
        view.split = entity.split;
        view.weight = entity.weight;
        view.status = entity.status == null ? null : entity.status.name();
        view.executionResult = entity.executionResult == null ? null : entity.executionResult.name();
        view.taskResult = entity.taskResult == null ? null : entity.taskResult.name();
        view.agentRunId = entity.agentRunId;
        view.actualOutput = entity.actualOutput;
        view.gradingJson = entity.gradingJson;
        view.usageJson = entity.usageJson;
        view.error = entity.error;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        view.traceJson = entity.traceJson;
        view.artifactsJson = entity.artifactsJson;
        if (evalCase != null) {
            var body = JsonUtil.fromJson(EvalBodies.CaseBody.class, evalCase.bodyJson);
            view.caseInputJson = body.input == null ? null : JsonUtil.toJson(body.input);
            if (oracle) view.caseReferenceJson = body.reference == null ? null : JsonUtil.toJson(body.reference);
        }
        return view;
    }

    public static RunReportView report(EvalBodies.RunReport report) {
        var view = new RunReportView();
        view.plannedTrials = report.plannedTrials;
        view.success = report.success;
        view.failure = report.failure;
        view.unmeasured = report.unmeasured;
        view.successLowerBound = report.successLowerBound;
        view.successUpperBound = report.successUpperBound;
        view.measurementCoverage = report.measurementCoverage;
        view.measuredSuccessRate = report.measuredSuccessRate;
        view.weightedSuccessLowerBound = report.weightedSuccessLowerBound;
        view.weightedSuccessUpperBound = report.weightedSuccessUpperBound;
        view.p50DurationMs = report.p50DurationMs;
        view.p95DurationMs = report.p95DurationMs;
        view.inputTokens = report.inputTokens;
        view.outputTokens = report.outputTokens;
        view.costKnown = report.costKnown;
        view.notes = report.notes;
        view.cases = new ArrayList<>();
        if (report.cases != null) {
            for (var item : report.cases) view.cases.add(caseReport(item));
        }
        view.graders = new ArrayList<>();
        if (report.graders != null) {
            for (var item : report.graders) view.graders.add(graderReport(item));
        }
        return view;
    }

    public static ComparisonView comparison(EvalBodies.ComparisonReport report) {
        var view = new ComparisonView();
        view.grade = report.grade;
        view.differences = report.differences;
        view.pairedCases = report.pairedCases;
        view.candidateWins = report.candidateWins;
        view.ties = report.ties;
        view.baselineWins = report.baselineWins;
        view.meanDelta = report.meanDelta;
        view.ciLow = report.ciLow;
        view.ciHigh = report.ciHigh;
        view.regressions = report.regressions;
        view.newHardFailures = report.newHardFailures;
        view.statisticsVersion = report.statisticsVersion;
        view.cases = new ArrayList<>();
        if (report.cases != null) {
            for (var item : report.cases) view.cases.add(caseDelta(item));
        }
        return view;
    }

    private static void copyTrial(EvalTrialEntity entity, TrialView view) {
        view.id = entity.id;
        view.runId = entity.runId;
        view.caseId = entity.caseId;
        view.repetitionIndex = entity.repetitionIndex;
        view.split = entity.split;
        view.weight = entity.weight;
        view.status = entity.status == null ? null : entity.status.name();
        view.executionResult = entity.executionResult == null ? null : entity.executionResult.name();
        view.taskResult = entity.taskResult == null ? null : entity.taskResult.name();
        view.agentRunId = entity.agentRunId;
        view.actualOutput = entity.actualOutput;
        view.gradingJson = entity.gradingJson;
        view.usageJson = entity.usageJson;
        view.error = entity.error;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
    }

    private static CaseReportView caseReport(EvalBodies.CaseReport report) {
        var view = new CaseReportView();
        view.caseId = report.caseId;
        view.split = report.split;
        view.weight = report.weight;
        view.success = report.success;
        view.failure = report.failure;
        view.unmeasured = report.unmeasured;
        view.successFraction = report.successFraction;
        view.anySuccess = report.anySuccess;
        view.allSuccess = report.allSuccess;
        view.trials = new ArrayList<>();
        if (report.trials != null) {
            for (var item : report.trials) view.trials.add(trialSummary(item));
        }
        return view;
    }

    private static TrialSummaryView trialSummary(EvalBodies.TrialSummary summary) {
        var view = new TrialSummaryView();
        view.repetitionIndex = summary.repetitionIndex;
        view.executionResult = summary.executionResult;
        view.taskResult = summary.taskResult;
        view.durationMs = summary.durationMs;
        view.inputTokens = summary.inputTokens;
        view.outputTokens = summary.outputTokens;
        return view;
    }

    private static GraderReportView graderReport(EvalBodies.GraderReport report) {
        var view = new GraderReportView();
        view.graderId = report.graderId;
        view.required = report.required;
        view.applicable = report.applicable;
        view.completed = report.completed;
        view.pass = report.pass;
        view.fail = report.fail;
        view.indeterminate = report.indeterminate;
        view.error = report.error;
        view.notRunTargetFailed = report.notRunTargetFailed;
        view.passRate = report.passRate;
        return view;
    }

    private static CaseDeltaView caseDelta(EvalBodies.CaseDelta delta) {
        var view = new CaseDeltaView();
        view.caseId = delta.caseId;
        view.baselineSuccessFraction = delta.baselineSuccessFraction;
        view.candidateSuccessFraction = delta.candidateSuccessFraction;
        view.delta = delta.delta;
        return view;
    }

    private EvaluationViews() {
    }
}
