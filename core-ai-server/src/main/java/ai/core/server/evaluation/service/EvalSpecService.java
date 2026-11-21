package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.DatasetRevisionEntity;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalCaseEntity;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalSpecEntity;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Freezes the measurement protocol of a formal run.
 *
 * @author stephen
 */
public class EvalSpecService {
    public static final int DEFAULT_REPETITIONS = 3;
    public static final int MAX_REPETITIONS = 10;
    public static final int DEFAULT_AGENT_CONCURRENCY = 2;
    public static final int MAX_AGENT_CONCURRENCY = 10;
    public static final int DEFAULT_TRIAL_DEADLINE_SECONDS = 180;
    public static final int MAX_TRIAL_DEADLINE_SECONDS = 900;
    public static final int DEFAULT_TRIAL_MAX_TURNS = 30;
    public static final int MAX_TRIAL_MAX_TURNS = 100;
    public static final int DEFAULT_RUN_DEADLINE_SECONDS = 6 * 3600;
    public static final int MAX_RUN_DEADLINE_SECONDS = 24 * 3600;

    @Inject
    EvaluationStore evaluationStore;
    @Inject
    EvalGraderSuiteService graderSuiteService;

    public EvalSpecEntity create(EvaluationEntity evaluation, EvalSpecDraft draft, String actorId) {
        var dataset = resolveDataset(evaluation, draft.datasetId());
        var suite = resolveSuite(evaluation, draft.graderSuiteId());
        var split = parseSplit(draft.split());
        var selected = selectCases(dataset, split, draft.caseIds());
        var body = buildBody(dataset, suite, split, selected, draft);
        validateCoverage(suite, selected);
        var fingerprint = fingerprint(body, dataset, suite);
        var existing = evaluationStore.findSpecByFingerprint(evaluation.id, fingerprint).orElse(null);
        if (existing != null) return existing;
        return insert(evaluation, dataset, suite, body, fingerprint, actorId);
    }

    public int plannedTrials(EvalBodies.EvalSpecBody body) {
        return body.caseIds.size() * body.repetitions;
    }

    public List<EvalCaseEntity> selectedCases(EvalBodies.EvalSpecBody body) {
        return evaluationStore.listCasesByIds(body.datasetRevisionId, body.caseIds);
    }

    private DatasetRevisionEntity resolveDataset(EvaluationEntity evaluation, String datasetId) {
        var dataset = evaluationStore.getDatasetRevisionById(required(datasetId, "datasetId"))
            .orElseThrow(() -> new EvalValidationException("datasetId", "dataset revision not found"));
        if (!evaluation.id.equals(dataset.evaluationId)) {
            throw new EvalValidationException("datasetId", "dataset does not belong to this evaluation");
        }
        return dataset;
    }

    private GraderSuiteEntity resolveSuite(EvaluationEntity evaluation, String suiteId) {
        var suite = evaluationStore.getGraderSuiteById(required(suiteId, "graderSuiteId"))
            .orElseThrow(() -> new EvalValidationException("graderSuiteId", "grader suite not found"));
        if (!evaluation.id.equals(suite.evaluationId)) {
            throw new EvalValidationException("graderSuiteId", "grader suite does not belong to this evaluation");
        }
        return suite;
    }

    private void validateCoverage(GraderSuiteEntity suite, List<EvalCaseEntity> selected) {
        var suiteBody = JsonUtil.fromJson(EvalBodies.GraderSuiteBody.class, suite.bodyJson);
        for (var evalCase : selected) {
            var caseBody = JsonUtil.fromJson(EvalBodies.CaseBody.class, evalCase.bodyJson);
            var errors = graderSuiteService.validateCaseCoverage(suiteBody, caseBody);
            if (!errors.isEmpty()) {
                throw new EvalValidationException("case:" + evalCase.caseId, String.join("; ", errors));
            }
        }
    }

    private EvalBodies.EvalSpecBody buildBody(DatasetRevisionEntity dataset, GraderSuiteEntity suite,
                                              EvalEnums.Split split, List<EvalCaseEntity> selected, EvalSpecDraft draft) {
        var body = new EvalBodies.EvalSpecBody();
        body.datasetRevisionId = dataset.id;
        body.datasetFingerprint = dataset.contentHash;
        body.graderSuiteId = suite.id;
        body.profile = parseProfile(draft.profile()).name();
        body.repetitions = clamp(draft.repetitions(), DEFAULT_REPETITIONS, 1, MAX_REPETITIONS, "repetitions");
        body.agentConcurrency = clamp(draft.agentConcurrency(), DEFAULT_AGENT_CONCURRENCY, 1,
            MAX_AGENT_CONCURRENCY, "agentConcurrency");
        body.trialDeadlineSeconds = clamp(draft.trialDeadlineSeconds(), DEFAULT_TRIAL_DEADLINE_SECONDS, 1,
            MAX_TRIAL_DEADLINE_SECONDS, "trialDeadlineSeconds");
        body.trialMaxTurns = clamp(draft.trialMaxTurns(), DEFAULT_TRIAL_MAX_TURNS, 1, MAX_TRIAL_MAX_TURNS, "trialMaxTurns");
        body.runDeadlineSeconds = clamp(draft.runDeadlineSeconds(), DEFAULT_RUN_DEADLINE_SECONDS, 60,
            MAX_RUN_DEADLINE_SECONDS, "runDeadlineSeconds");
        body.defaultEvaluationClock = parseClock(draft.defaultClock());
        body.split = split.name();
        var caseIds = new ArrayList<String>();
        for (var evalCase : selected) caseIds.add(evalCase.caseId);
        caseIds.sort(String::compareTo);
        body.caseIds = caseIds;
        return body;
    }

    private String fingerprint(EvalBodies.EvalSpecBody body, DatasetRevisionEntity dataset, GraderSuiteEntity suite) {
        var fingerprintBody = JsonUtil.fromJson(EvalBodies.EvalSpecBody.class, JsonUtil.toJson(body));
        fingerprintBody.datasetRevisionId = null;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("body", fingerprintBody);
        payload.put("dataset_hash", dataset.contentHash);
        payload.put("grader_fingerprint", suite.fingerprint);
        payload.put("canonical", EvalHashing.CANONICAL_VERSION);
        return EvalHashing.hashObject(payload);
    }

    private EvalSpecEntity insert(EvaluationEntity evaluation, DatasetRevisionEntity dataset, GraderSuiteEntity suite,
                                  EvalBodies.EvalSpecBody body, String fingerprint, String actorId) {
        var entity = new EvalSpecEntity();
        entity.id = UUID.randomUUID().toString();
        entity.evaluationId = evaluation.id;
        entity.revision = evaluationStore.nextSpecRevision(evaluation.id);
        entity.datasetRevisionId = dataset.id;
        entity.graderSuiteId = suite.id;
        entity.fingerprint = fingerprint;
        entity.bodyJson = JsonUtil.toJson(body);
        entity.createdBy = actorId;
        entity.createdAt = ZonedDateTime.now();
        evaluationStore.insertSpec(entity);
        return entity;
    }

    private List<EvalCaseEntity> selectCases(DatasetRevisionEntity dataset, EvalEnums.Split split, List<String> caseIds) {
        if (caseIds != null && !caseIds.isEmpty()) {
            var requested = new LinkedHashSet<String>();
            for (var id : caseIds) {
                if (id == null || id.isBlank()) throw new EvalValidationException("caseIds", "case ids must not be blank");
                requested.add(id);
            }
            var selected = evaluationStore.listCasesByIds(dataset.id, new ArrayList<>(requested));
            if (selected.size() != requested.size()) {
                throw new EvalValidationException("caseIds", "case selection contains ids that are not in the dataset revision");
            }
            return selected;
        }
        var selected = evaluationStore.listCases(dataset.id, split.name(), 0, 1000);
        if (selected.isEmpty()) {
            throw new EvalValidationException("split", "selected split / case selection is empty");
        }
        return selected;
    }

    private EvalEnums.Profile parseProfile(String profile) {
        if (profile == null || profile.isBlank()) return EvalEnums.Profile.OFFLINE_TEXT;
        try {
            var parsed = EvalEnums.Profile.valueOf(profile);
            if (parsed != EvalEnums.Profile.OFFLINE_TEXT) {
                throw new EvalValidationException("profile", "profile is not available in this phase: " + profile);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new EvalValidationException("profile", "unsupported profile: " + profile, e);
        }
    }

    private EvalEnums.Split parseSplit(String split) {
        if (split == null || split.isBlank()) return EvalEnums.Split.DEV;
        try {
            return EvalEnums.Split.valueOf(split);
        } catch (IllegalArgumentException e) {
            throw new EvalValidationException("split", "expected DEV|VALIDATION|HOLDOUT", e);
        }
    }

    private String parseClock(String clock) {
        ZonedDateTime value;
        if (clock == null || clock.isBlank()) {
            value = ZonedDateTime.now(ZoneOffset.UTC).withNano(0);
        } else {
            try {
                value = ZonedDateTime.parse(clock);
            } catch (DateTimeParseException e) {
                throw new EvalValidationException("defaultClock", "must be ISO-8601 with offset", e);
            }
        }
        return value.toString();
    }

    private int clamp(Integer value, int defaultValue, int min, int max, String field) {
        var resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) {
            throw new EvalValidationException(field, "must be between " + min + " and " + max);
        }
        return resolved;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new EvalValidationException(field, "is required");
        return value;
    }
}
