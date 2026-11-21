package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvaluationEntity;
import ai.core.server.evaluation.domain.GraderSuiteEntity;
import ai.core.server.evaluation.grading.EvalGraderEngine;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class EvalGraderSuiteService {
    @Inject
    EvaluationStore evaluationStore;
    @Inject
    EvalGraderEngine graderEngine;

    public EvalBodies.GraderSuiteBody parse(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) throw new EvalValidationException("body", "suite body is required");
        try {
            var suite = JsonUtil.fromJson(EvalBodies.GraderSuiteBody.class, bodyJson);
            graderEngine.validateSuite(suite);
            return suite;
        } catch (EvalValidationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new EvalValidationException("body", e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new EvalValidationException("body", "invalid suite JSON: " + e.getMessage(), e);
        }
    }

    public String test(String bodyJson) {
        var suite = parse(bodyJson);
        var checks = new ArrayList<Map<String, Object>>();
        for (var grader : suite.graders) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("grader_id", grader.id);
            check.put("kind", grader.kind);
            check.put("valid", Boolean.TRUE);
            check.put("mode", grader.mode == null ? "BOOLEAN" : grader.mode);
            checks.add(check);
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", "STRUCTURAL_ONLY");
        receipt.put("validated_at", ZonedDateTime.now().toString());
        receipt.put("success_policy", EvalGraderEngine.SUCCESS_POLICY);
        receipt.put("checks", checks);
        receipt.put("note", "positive/negative sample execution is deferred until the isolated grader backend exists");
        return JsonUtil.toJson(receipt);
    }

    public GraderSuiteEntity create(EvaluationEntity evaluation, String bodyJson, String name, String actorId) {
        var suiteBody = parse(bodyJson);
        var entity = new GraderSuiteEntity();
        entity.id = UUID.randomUUID().toString();
        entity.evaluationId = evaluation.id;
        entity.revision = evaluationStore.nextGraderSuiteRevision(evaluation.id);
        entity.name = name == null || name.isBlank() ? "suite-" + entity.revision : name.trim();
        entity.fingerprint = EvalHashing.hashObject(suiteBody);
        entity.bodyJson = JsonUtil.toJson(suiteBody);
        var receipt = test(bodyJson);
        entity.testReceiptJson = receipt;
        entity.createdBy = actorId;
        entity.createdAt = ZonedDateTime.now();
        evaluationStore.insertGraderSuite(entity);
        return entity;
    }

    public List<String> validateCaseCoverage(EvalBodies.GraderSuiteBody suite, EvalBodies.CaseBody caseBody) {
        var errors = new ArrayList<String>();
        var applicable = graderEngine.splitApplicable(suite, caseBody).applicable;
        if (applicable.isEmpty()) {
            errors.add("no applicable grader in suite for grader_refs");
            return errors;
        }
        var required = graderEngine.splitApplicable(suite, caseBody).required;
        if (required.isEmpty()) {
            errors.add("no applicable required grader for grader_refs");
        }
        for (var grader : applicable) {
            var kind = grader.kind;
            if (("EXACT".equals(kind) || "CONTAINS".equals(kind))
                && (caseBody.reference == null
                || caseBody.reference.output == null && (caseBody.reference.answers == null || caseBody.reference.answers.isEmpty()))) {
                errors.add("grader " + grader.id + " requires reference.output or reference.answers");
            }
            if ("JSON_ASSERTION".equals(kind)
                && (caseBody.reference == null || caseBody.reference.assertions == null || caseBody.reference.assertions.isEmpty())) {
                errors.add("grader " + grader.id + " requires reference.assertions");
            }
        }
        return errors;
    }
}
