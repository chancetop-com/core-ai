package ai.core.server.evaluation;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalRunEntity;
import ai.core.server.evaluation.service.EvalComparator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class EvalComparatorTest {
    private final EvalComparator comparator = new EvalComparator();

    @Test
    void pairedCaseDeltaUsesPerCaseFractions() {
        var input = new EvalComparator.ComparisonInput(run(), run(), spec("suite"), spec("suite"),
            report(caseReport("c1", 2, 0), caseReport("c2", 1, 1)),
            report(caseReport("c1", 1, 1), caseReport("c2", 2, 0)), true, true);
        var result = comparator.compare(input);
        assertEquals(EvalEnums.ComparisonGrade.STRICT.name(), result.grade);
        assertEquals(2, result.pairedCases);
        assertEquals(1, result.candidateWins);
        assertEquals(1, result.baselineWins);
        assertEquals(0.0, result.meanDelta, 1e-9);
        assertEquals(2, result.cases.size());
    }

    @Test
    void changedGraderSuiteIsIncompatibleAndSkipsStatistics() {
        var input = new EvalComparator.ComparisonInput(run(), run(), spec("suite-a"), spec("suite-b"),
            report(caseReport("c1", 1, 1)), report(caseReport("c1", 2, 0)), true, true);
        var result = comparator.compare(input);
        assertEquals(EvalEnums.ComparisonGrade.INCOMPATIBLE.name(), result.grade);
        assertNull(result.statisticsVersion);
        assertNull(result.meanDelta);
    }

    private EvalRunEntity run() {
        var run = new EvalRunEntity();
        run.status = EvalEnums.RunStatus.COMPLETED;
        return run;
    }

    private EvalBodies.EvalSpecBody spec(String suiteId) {
        var spec = new EvalBodies.EvalSpecBody();
        spec.datasetRevisionId = "dataset";
        spec.datasetFingerprint = "dataset-hash";
        spec.graderSuiteId = suiteId;
        spec.profile = "OFFLINE_TEXT";
        spec.repetitions = 2;
        spec.agentConcurrency = 2;
        spec.trialDeadlineSeconds = 180;
        spec.trialMaxTurns = 30;
        spec.defaultEvaluationClock = "2026-11-21T00:00:00Z";
        spec.caseIds = List.of("c1", "c2");
        return spec;
    }

    private EvalBodies.RunReport report(EvalBodies.CaseReport... cases) {
        var report = new EvalBodies.RunReport();
        report.cases = List.of(cases);
        return report;
    }

    private EvalBodies.CaseReport caseReport(String caseId, int success, int failure) {
        var report = new EvalBodies.CaseReport();
        report.caseId = caseId;
        report.weight = 1.0;
        report.success = success;
        report.failure = failure;
        report.unmeasured = 0;
        return report;
    }
}
