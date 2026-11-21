package ai.core.server.evaluation;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.grading.EvalGraderEngine;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class EvalGraderEngineTest {
    private final EvalGraderEngine engine = new EvalGraderEngine();

    @Test
    void exactUsesDeclaredNormalization() {
        var suite = suite("""
            {"graders":[{"id":"exact","kind":"EXACT","required":true,"mode":"BOOLEAN","normalization":"TRIM"}]}
            """);
        var caseBody = caseBody("""
            {"caseId":"c1","input":{"text":"2+2"},"reference":{"output":" 4 "},"graderRefs":["exact"]}
            """);
        var bundle = engine.grade(suite, caseBody, "4", EvalEnums.ExecutionResult.SUCCEEDED);
        assertEquals(EvalEnums.Verdict.PASS.name(), bundle.results.get(0).verdict);
        assertEquals(1.0, bundle.results.get(0).score);
    }

    @Test
    void jsonAssertionIsEvaluatedAgainstActualOutput() {
        var suite = suite("""
            {"graders":[{"id":"json","kind":"JSON_ASSERTION","required":true,"mode":"BOOLEAN"}]}
            """);
        var caseBody = caseBody("""
            {"caseId":"c1","input":{"text":"sum"},"reference":{"assertions":[{"kind":"JSON_NUMBER_EQUALS","jsonPointer":"/total","expectedNumber":120.0,"tolerance":0.001}]},"graderRefs":["json"]}
            """);
        var pass = engine.grade(suite, caseBody, "{\"total\":120.0005}", EvalEnums.ExecutionResult.SUCCEEDED);
        assertEquals(EvalEnums.Verdict.PASS.name(), pass.results.get(0).verdict);
        var fail = engine.grade(suite, caseBody, "{\"total\":117}", EvalEnums.ExecutionResult.SUCCEEDED);
        assertEquals(EvalEnums.Verdict.FAIL.name(), fail.results.get(0).verdict);
        assertEquals("ACTUAL_JSON", fail.results.get(0).evidence.get(0).kind);
        var malformed = engine.grade(suite, caseBody, "not json", EvalEnums.ExecutionResult.SUCCEEDED);
        assertEquals(EvalEnums.Verdict.FAIL.name(), malformed.results.get(0).verdict);
    }

    @Test
    void failedTargetNeverRunsGrader() {
        var suite = suite("""
            {"graders":[{"id":"exact","kind":"EXACT","required":true,"mode":"BOOLEAN"}]}
            """);
        var caseBody = caseBody("""
            {"caseId":"c1","input":{"text":"2+2"},"reference":{"output":"4"},"graderRefs":["exact"]}
            """);
        var bundle = engine.grade(suite, caseBody, null, EvalEnums.ExecutionResult.TIMEOUT);
        assertEquals(EvalEnums.GradingStatus.NOT_RUN_TARGET_FAILED.name(), bundle.results.get(0).status);
        assertNull(bundle.results.get(0).verdict);
    }

    @Test
    void suiteValidationRejectsUnsafeAndEmptySuites() {
        assertThrows(IllegalArgumentException.class, () -> engine.validateSuite(suite("""
            {"graders":[]}
            """)));
        assertThrows(IllegalArgumentException.class, () -> engine.validateSuite(suite("""
            {"graders":[{"id":"a","kind":"REGEX","required":true,"pattern":"(a+)+"}]}
            """)));
        assertThrows(IllegalArgumentException.class, () -> engine.validateSuite(suite("""
            {"graders":[{"id":"a","kind":"EXACT","required":true,"mode":"BOUNDED_SCORE","failMax":0.9,"passMin":0.2}]}
            """)));
        assertThrows(IllegalArgumentException.class, () -> engine.validateSuite(suite("""
            {"graders":[{"id":"a","kind":"EXACT","required":false}]}
            """)));
    }

    private EvalBodies.GraderSuiteBody suite(String json) {
        return JsonUtil.fromJson(EvalBodies.GraderSuiteBody.class, json);
    }

    private EvalBodies.CaseBody caseBody(String json) {
        return JsonUtil.fromJson(EvalBodies.CaseBody.class, json);
    }
}
