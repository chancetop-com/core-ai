package ai.core.server.evaluation;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.server.evaluation.service.EvalReportBuilder;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class EvalReportBuilderTest {
    private final EvalReportBuilder builder = new EvalReportBuilder();

    @Test
    void plannedDenominatorKeepsMissingResults() {
        var trials = new ArrayList<EvalTrialEntity>();
        for (int i = 0; i < 6; i++) trials.add(trial("c" + i, EvalEnums.TaskResult.SUCCESS));
        for (int i = 6; i < 8; i++) trials.add(trial("c" + i, EvalEnums.TaskResult.FAILURE));
        for (int i = 8; i < 10; i++) trials.add(trial("c" + i, EvalEnums.TaskResult.UNMEASURED));
        var report = builder.build(spec(10), trials);
        assertEquals(10, report.plannedTrials);
        assertEquals(6, report.success);
        assertEquals(2, report.failure);
        assertEquals(2, report.unmeasured);
        assertEquals(0.6, report.successLowerBound);
        assertEquals(0.8, report.successUpperBound);
        assertEquals(0.8, report.measurementCoverage);
        assertEquals(0.75, report.measuredSuccessRate);
        assertEquals(0.6, report.weightedSuccessLowerBound);
        assertEquals(0.8, report.weightedSuccessUpperBound);
    }

    @Test
    void repeatTriStateDoesNotHideUnknownOrKnownFailure() {
        var trials = List.of(
            trial("mixed", EvalEnums.TaskResult.SUCCESS),
            trial("mixed", EvalEnums.TaskResult.FAILURE),
            trial("unknown", EvalEnums.TaskResult.UNMEASURED),
            trial("failed-unknown", EvalEnums.TaskResult.FAILURE),
            trial("failed-unknown", EvalEnums.TaskResult.UNMEASURED));
        var report = builder.build(spec(1), trials);
        var mixed = report.cases.stream().filter(c -> "mixed".equals(c.caseId)).findFirst().orElseThrow();
        assertTrue(mixed.anySuccess);
        assertFalse(mixed.allSuccess);
        assertEquals(0.5, mixed.successFraction);
        var unknown = report.cases.stream().filter(c -> "unknown".equals(c.caseId)).findFirst().orElseThrow();
        assertNull(unknown.anySuccess);
        assertNull(unknown.allSuccess);
        var failedUnknown = report.cases.stream().filter(c -> "failed-unknown".equals(c.caseId)).findFirst().orElseThrow();
        assertNull(failedUnknown.anySuccess);
        assertFalse(failedUnknown.allSuccess);
    }

    @Test
    void emptySelectionDoesNotClaimSuccess() {
        var report = builder.build(spec(0), List.of());
        assertEquals(0, report.plannedTrials);
        assertNull(report.successLowerBound);
        assertNull(report.measuredSuccessRate);
        assertTrue(report.notes.stream().anyMatch(note -> note.contains("cost")));
    }

    private EvalBodies.EvalSpecBody spec(int caseCount) {
        var spec = new EvalBodies.EvalSpecBody();
        spec.repetitions = 1;
        spec.caseIds = new ArrayList<>(caseCount);
        for (int i = 0; i < caseCount; i++) spec.caseIds.add("c" + i);
        return spec;
    }

    private EvalTrialEntity trial(String caseId, EvalEnums.TaskResult result) {
        var trial = new EvalTrialEntity();
        trial.caseId = caseId;
        trial.split = "DEV";
        trial.weight = 1.0;
        trial.taskResult = result;
        trial.executionResult = result == EvalEnums.TaskResult.UNMEASURED
            ? EvalEnums.ExecutionResult.PLATFORM_ERROR : EvalEnums.ExecutionResult.SUCCEEDED;
        trial.startedAt = ZonedDateTime.now().minusSeconds(2);
        trial.completedAt = ZonedDateTime.now();
        return trial;
    }
}
