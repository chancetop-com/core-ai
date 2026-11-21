package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.BaselineHistoryEntity;
import ai.core.server.evaluation.domain.CandidateSnapshotEntity;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.store.EvalRunStore;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Preflight, durable run creation, cancellation, baseline selection and paired comparison.
 *
 * @author stephen
 */
public class EvalRunService {
    @Inject
    EvaluationService evaluationService;
    @Inject
    EvaluationStore evaluationStore;
    @Inject
    EvalRunStore runStore;
    @Inject
    EvalSpecService specService;
    @Inject
    EvalSnapshotBuilder snapshotBuilder;
    @Inject
    EvalReportBuilder reportBuilder;
    @Inject
    EvalComparator comparator;
    @Inject
    EvalAgentRunGateway gateway;
    @Inject
    Database database;

    public EvalRunEntity start(String evaluationId, StartRunDraft draft, String actorId) {
        evaluationService.requireRun(evaluationId, actorId);
        validateRequestKey(draft.requestKey());
        var spec = requireSpec(evaluationId, draft.specId());
        var body = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, spec.bodyJson);
        requireReadyDataset(body);
        requireSuite(body);
        var cases = specService.selectedCases(body);
        if (cases.size() != body.caseIds.size()) {
            throw new BadRequestException("dataset selection no longer matches the frozen spec");
        }
        var requestHash = requestHash(draft, spec);
        var existing = runStore.findByRequestKey(evaluationId, actorId, draft.requestKey()).orElse(null);
        if (existing != null) return requireSameRequest(existing, requestHash);
        var snapshot = resolveSnapshot(evaluationId, draft, actorId);
        var run = newRun(evaluationId, draft, actorId, spec, snapshot, body);
        var trials = planTrials(run, body, cases);
        try (var transaction = database.beginTransaction()) {
            runStore.insertRun(run);
            runStore.insertTrials(trials);
            transaction.commit();
        } catch (RuntimeException e) {
            var raced = runStore.findByRequestKey(evaluationId, actorId, draft.requestKey()).orElse(null);
            if (raced != null) return requireSameRequest(raced, requestHash);
            throw e;
        }
        return run;
    }

    private void validateRequestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank()) throw new BadRequestException("requestKey is required");
        if (requestKey.length() > 200) throw new BadRequestException("requestKey is too long");
    }

    private EvalSpecEntity requireSpec(String evaluationId, String specId) {
        var spec = evaluationStore.getSpecById(required(specId, "specId"))
            .orElseThrow(() -> new BadRequestException("spec not found: " + specId));
        if (!evaluationId.equals(spec.evaluationId)) throw new BadRequestException("spec does not belong to this evaluation");
        return spec;
    }

    private void requireReadyDataset(EvalBodies.EvalSpecBody body) {
        var dataset = evaluationStore.getDatasetRevisionById(body.datasetRevisionId)
            .orElseThrow(() -> new BadRequestException("dataset revision is missing"));
        if (!"READY".equals(dataset.status)) throw new BadRequestException("dataset revision is not READY");
    }

    private void requireSuite(EvalBodies.EvalSpecBody body) {
        evaluationStore.getGraderSuiteById(body.graderSuiteId)
            .orElseThrow(() -> new BadRequestException("grader suite is missing"));
    }

    private String requestHash(StartRunDraft draft, EvalSpecEntity spec) {
        return EvalHashing.hashObject(Map.of(
            "specId", spec.id,
            "source", draft.source() == null ? "" : draft.source(),
            "snapshotId", draft.snapshotId() == null ? "" : draft.snapshotId(),
            "purpose", draft.purpose() == null ? "" : draft.purpose(),
            "experimentLabel", draft.experimentLabel() == null ? "" : draft.experimentLabel()));
    }

    private CandidateSnapshotEntity resolveSnapshot(String evaluationId, StartRunDraft draft, String actorId) {
        if (draft.snapshotId() != null && !draft.snapshotId().isBlank()) {
            var snapshot = evaluationStore.getSnapshot(draft.snapshotId())
                .orElseThrow(() -> new BadRequestException("candidate snapshot not found: " + draft.snapshotId()));
            if (!evaluationId.equals(snapshot.evaluationId)) {
                throw new BadRequestException("candidate snapshot does not belong to this evaluation");
            }
            return snapshot;
        }
        var source = parseSource(draft.source());
        var evaluation = evaluationStore.getEvaluation(evaluationId)
            .orElseThrow(() -> new BadRequestException("evaluation not found: " + evaluationId));
        return snapshotBuilder.build(evaluation, source, actorId);
    }

    private EvalRunEntity newRun(String evaluationId, StartRunDraft draft, String actorId,
                                 EvalSpecEntity spec, CandidateSnapshotEntity snapshot, EvalBodies.EvalSpecBody body) {
        var now = ZonedDateTime.now();
        var run = new EvalRunEntity();
        run.id = UUID.randomUUID().toString();
        run.evaluationId = evaluationId;
        run.actorId = actorId;
        run.requestKey = draft.requestKey();
        run.requestHash = requestHash(draft, spec);
        run.specId = spec.id;
        run.snapshotId = snapshot.id;
        run.purpose = parsePurpose(draft.purpose());
        run.experimentLabel = draft.experimentLabel();
        run.status = EvalEnums.RunStatus.RUNNING;
        run.plannedTrials = body.caseIds.size() * body.repetitions;
        run.startedTrials = 0;
        run.succeededTrials = 0;
        run.failedTrials = 0;
        run.unmeasuredTrials = 0;
        run.deadlineAt = now.plusSeconds(body.runDeadlineSeconds);
        run.createdAt = now;
        run.startedAt = now;
        return run;
    }

    public List<EvalRunEntity> listRuns(String evaluationId, String actorId, int offset, int limit) {
        evaluationService.requireVisible(evaluationId, actorId);
        return runStore.listRuns(evaluationId, offset, limit);
    }

    public long countRuns(String evaluationId, String actorId) {
        evaluationService.requireVisible(evaluationId, actorId);
        return runStore.countRuns(evaluationId);
    }

    public EvalRunEntity getRun(String evaluationId, String runId, String actorId) {
        evaluationService.requireVisible(evaluationId, actorId);
        var run = runStore.getRun(runId)
            .orElseThrow(() -> new NotFoundException("evaluation run not found: " + runId));
        if (!evaluationId.equals(run.evaluationId)) throw new NotFoundException("evaluation run not found: " + runId);
        return run;
    }

    public List<EvalTrialEntity> listTrials(String evaluationId, String runId, String actorId, TrialQuery query) {
        getRun(evaluationId, runId, actorId);
        return runStore.listTrials(runId, query.status(), query.caseId(), query.taskResult(), query.offset(), query.limit());
    }

    public long countTrials(String evaluationId, String runId, String actorId, TrialQuery query) {
        getRun(evaluationId, runId, actorId);
        return runStore.countTrials(runId, query.status(), query.caseId(), query.taskResult());
    }

    public EvalTrialEntity getTrial(String evaluationId, String runId, String trialId, String actorId) {
        getRun(evaluationId, runId, actorId);
        var trial = runStore.getTrial(trialId)
            .orElseThrow(() -> new NotFoundException("evaluation trial not found: " + trialId));
        if (!runId.equals(trial.runId)) throw new NotFoundException("evaluation trial not found: " + trialId);
        return trial;
    }

    public EvalCaseEntity caseForTrial(String evaluationId, String runId, String trialId, String actorId) {
        var run = getRun(evaluationId, runId, actorId);
        var trial = getTrial(evaluationId, runId, trialId, actorId);
        var spec = evaluationStore.getSpecById(run.specId).orElseThrow();
        var body = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, spec.bodyJson);
        var cases = evaluationStore.listCasesByIds(body.datasetRevisionId, List.of(trial.caseId));
        return cases.isEmpty() ? null : cases.get(0);
    }

    public EvalBodies.RunReport report(String evaluationId, String runId, String actorId) {
        return buildReport(getRun(evaluationId, runId, actorId));
    }

    private EvalBodies.RunReport buildReport(EvalRunEntity run) {
        if (run.completedAt != null && run.reportJson != null && !run.reportJson.isBlank()) {
            return JsonUtil.fromJson(EvalBodies.RunReport.class, run.reportJson);
        }
        var spec = evaluationStore.getSpecById(run.specId).orElseThrow();
        var body = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, spec.bodyJson);
        return reportBuilder.build(body, runStore.listAllTrials(run.id));
    }

    public void cancel(String evaluationId, String runId, String actorId) {
        var run = getRun(evaluationId, runId, actorId);
        evaluationService.requireRun(evaluationId, actorId);
        if (isTerminal(run.status)) return;
        runStore.requestCancel(runId, actorId, ZonedDateTime.now());
        runStore.markPendingNotExecuted(runId, ZonedDateTime.now());
        for (var trial : runStore.listActiveTrials(runId)) {
            gateway.cancel(trial.agentRunId);
        }
        finalizeIfDone(runId);
    }

    public EvaluationEntity chooseBaseline(String evaluationId, String runId, String reason,
                                           boolean acknowledgeUnpinned, int expectedGeneration, String actorId) {
        var evaluation = evaluationService.requireManage(evaluationId, actorId);
        var run = getRun(evaluationId, runId, actorId);
        var eligibility = baselineIneligibility(run);
        if (eligibility != null) throw new BadRequestException("run is not eligible as baseline: " + eligibility);
        var snapshot = evaluationStore.getSnapshot(run.snapshotId).orElseThrow();
        var snapshotBody = JsonUtil.fromJson(EvalBodies.CandidateSnapshotBody.class, snapshot.snapshotJson);
        if (!acknowledgeUnpinned && !Boolean.TRUE.equals(snapshotBody.modelVersionPinned)) {
            throw new BadRequestException("MODEL_VERSION_UNPINNED: acknowledge the warning before selecting this baseline");
        }
        var generation = expectedGeneration < 0 ? currentBaselineGeneration(evaluation) : expectedGeneration;
        var history = new BaselineHistoryEntity();
        history.id = UUID.randomUUID().toString();
        history.evaluationId = evaluationId;
        history.baselineRunId = runId;
        history.previousRunId = evaluation.baselineRunId;
        history.generation = generation + 1;
        history.reason = reason == null || reason.isBlank() ? "selected by owner" : reason;
        history.actorId = actorId;
        history.createdAt = ZonedDateTime.now();
        if (!runStore.chooseBaseline(evaluationId, runId, generation, generation + 1, history)) {
            throw new ConflictException("evaluation was modified by another request; refresh and retry");
        }
        return evaluationStore.getEvaluation(evaluationId).orElse(evaluation);
    }

    public EvalBodies.ComparisonReport compare(String evaluationId, String baselineRunId, String candidateRunId, String actorId) {
        evaluationService.requireVisible(evaluationId, actorId);
        var baseline = getRun(evaluationId, baselineRunId, actorId);
        var candidate = getRun(evaluationId, candidateRunId, actorId);
        var baselineSpec = evaluationStore.getSpecById(baseline.specId).orElseThrow();
        var candidateSpec = evaluationStore.getSpecById(candidate.specId).orElseThrow();
        var baselineBody = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, baselineSpec.bodyJson);
        var candidateBody = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, candidateSpec.bodyJson);
        var baselineReport = reportBuilder.build(baselineBody, runStore.listAllTrials(baselineRunId));
        var candidateReport = reportBuilder.build(candidateBody, runStore.listAllTrials(candidateRunId));
        var input = new EvalComparator.ComparisonInput(baseline, candidate, baselineBody, candidateBody,
            baselineReport, candidateReport, snapshotPinned(baseline), snapshotPinned(candidate));
        return comparator.compare(input);
    }

    public void finalizeIfDone(String runId) {
        var run = runStore.getRun(runId).orElse(null);
        if (run == null || run.completedAt != null) return;
        if (runStore.countFinalTrials(runId) < run.plannedTrials) return;
        var trials = runStore.listAllTrials(runId);
        if (trials.size() < run.plannedTrials) return;
        var spec = evaluationStore.getSpecById(run.specId).orElse(null);
        if (spec == null) return;
        var body = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, spec.bodyJson);
        var report = reportBuilder.build(body, trials);
        run.reportJson = JsonUtil.toJson(report);
        run.succeededTrials = report.success;
        run.failedTrials = report.failure;
        run.unmeasuredTrials = report.unmeasured;
        if (run.status == EvalEnums.RunStatus.BUDGET_EXHAUSTED) {
            run.status = EvalEnums.RunStatus.BUDGET_EXHAUSTED;
        } else if (run.cancelRequestedBy != null || run.status == EvalEnums.RunStatus.CANCELLING) {
            run.status = EvalEnums.RunStatus.CANCELLED;
        } else if (report.unmeasured > 0) {
            run.status = EvalEnums.RunStatus.COMPLETED_WITH_ERRORS;
        } else {
            run.status = EvalEnums.RunStatus.COMPLETED;
        }
        run.completedAt = ZonedDateTime.now();
        runStore.finishRun(runId, run, run.completedAt);
    }

    private String baselineIneligibility(EvalRunEntity run) {
        if (run.purpose != EvalEnums.Purpose.FORMAL) return "only FORMAL runs can be baselines";
        if (run.status != EvalEnums.RunStatus.COMPLETED) return "run status is " + run.status;
        if (run.cancelRequestedBy != null || run.completedAt == null) return "run did not complete normally";
        var report = buildReport(run);
        if (report.unmeasured != null && report.unmeasured > 0) return "run has unmeasured trials";
        if (report.measurementCoverage == null || report.measurementCoverage < 1.0) return "measurement coverage is incomplete";
        if (report.graders != null) {
            for (var grader : report.graders) {
                if (!Boolean.TRUE.equals(grader.required)) continue;
                if (grader.error != null && grader.error > 0) return "required grader errored";
                if (grader.indeterminate != null && grader.indeterminate > 0) return "required grader was indeterminate";
            }
        }
        return null;
    }

    private boolean snapshotPinned(EvalRunEntity run) {
        var snapshot = evaluationStore.getSnapshot(run.snapshotId).orElse(null);
        if (snapshot == null) return false;
        var body = JsonUtil.fromJson(EvalBodies.CandidateSnapshotBody.class, snapshot.snapshotJson);
        return Boolean.TRUE.equals(body.modelVersionPinned);
    }

    private List<EvalTrialEntity> planTrials(EvalRunEntity run, EvalBodies.EvalSpecBody body, List<EvalCaseEntity> cases) {
        var now = ZonedDateTime.now();
        var trials = new ArrayList<EvalTrialEntity>(body.caseIds.size() * body.repetitions);
        for (var evalCase : cases) {
            for (var repetition = 0; repetition < body.repetitions; repetition++) {
                var trial = new EvalTrialEntity();
                trial.id = UUID.randomUUID().toString();
                trial.runId = run.id;
                trial.caseId = evalCase.caseId;
                trial.caseHash = evalCase.caseHash;
                trial.repetitionIndex = repetition;
                trial.split = evalCase.split;
                trial.weight = evalCase.weight;
                trial.status = EvalEnums.TrialStatus.PENDING;
                trial.generation = 0;
                trial.createdAt = now;
                trials.add(trial);
            }
        }
        return trials;
    }

    private int currentBaselineGeneration(EvaluationEntity evaluation) {
        return evaluation.baselineGeneration == null ? 0 : evaluation.baselineGeneration;
    }

    private EvalRunEntity requireSameRequest(EvalRunEntity existing, String requestHash) {
        if (!existing.requestHash.equals(requestHash)) {
            throw new ConflictException("idempotency key was already used with a different request");
        }
        return existing;
    }

    private EvalEnums.Source parseSource(String source) {
        if (source == null || source.isBlank()) return EvalEnums.Source.PUBLISHED;
        try {
            return EvalEnums.Source.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("source must be DRAFT or PUBLISHED", "BAD_REQUEST", e);
        }
    }

    private EvalEnums.Purpose parsePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) return EvalEnums.Purpose.FORMAL;
        try {
            return EvalEnums.Purpose.valueOf(purpose);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("purpose must be FORMAL, SMOKE or DIAGNOSTIC", "BAD_REQUEST", e);
        }
    }

    private boolean isTerminal(EvalEnums.RunStatus status) {
        return status == EvalEnums.RunStatus.COMPLETED || status == EvalEnums.RunStatus.COMPLETED_WITH_ERRORS
            || status == EvalEnums.RunStatus.BUDGET_EXHAUSTED || status == EvalEnums.RunStatus.CANCELLED
            || status == EvalEnums.RunStatus.FAILED;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        return value;
    }

    public record TrialQuery(String status, String caseId, String taskResult, int offset, int limit) {
    }
}
