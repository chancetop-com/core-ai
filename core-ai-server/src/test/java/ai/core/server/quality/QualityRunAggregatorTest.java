package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityRunAggregatorTest {
    @Test
    void blockFailRejectsEvenWhenOtherRulesPass() {
        var result = QualityRunAggregator.aggregate(List.of(
            outcome("A", QualityEnforcement.BLOCK, QualityIssueStatus.FAIL),
            outcome("B", QualityEnforcement.BLOCK, QualityIssueStatus.PASS),
            outcome("C", QualityEnforcement.WARN, QualityIssueStatus.PASS)));

        assertEquals(QualityGateResult.REJECTED, result.gateResult());
        assertTrue(result.checksComplete(), "a FAIL is a completed check; only ERROR/INDETERMINATE is incomplete");
    }

    @Test
    void blockErrorIsUnavailableNotRejected() {
        var result = QualityRunAggregator.aggregate(List.of(
            outcome("A", QualityEnforcement.BLOCK, QualityIssueStatus.ERROR),
            outcome("B", QualityEnforcement.BLOCK, QualityIssueStatus.PASS)));

        assertEquals(QualityGateResult.UNAVAILABLE, result.gateResult());
        assertFalse(result.checksComplete());
    }

    @Test
    void warnFailDoesNotChangeGateButWarnErrorMakesChecksIncomplete() {
        var onlyWarnFail = QualityRunAggregator.aggregate(List.of(
            outcome("A", QualityEnforcement.BLOCK, QualityIssueStatus.PASS),
            outcome("B", QualityEnforcement.WARN, QualityIssueStatus.FAIL)));
        assertEquals(QualityGateResult.PASSED, onlyWarnFail.gateResult());
        assertTrue(onlyWarnFail.checksComplete());

        var warnError = QualityRunAggregator.aggregate(List.of(
            outcome("A", QualityEnforcement.BLOCK, QualityIssueStatus.PASS),
            outcome("B", QualityEnforcement.WARN, QualityIssueStatus.ERROR)));
        assertEquals(QualityGateResult.PASSED, warnError.gateResult());
        assertFalse(warnError.checksComplete());
    }

    @Test
    void shadowFailureIsAuditOnly() {
        var result = QualityRunAggregator.aggregate(List.of(
            outcome("A", QualityEnforcement.BLOCK, QualityIssueStatus.PASS),
            outcome("B", QualityEnforcement.SHADOW, QualityIssueStatus.FAIL)));

        assertEquals(QualityGateResult.PASSED, result.gateResult());
        assertTrue(result.checksComplete());
        assertEquals(QualityEnforcement.SHADOW, result.issues().get(0).enforcement);
        assertEquals("sha256:", result.ruleResultDigest().substring(0, 7));
    }

    private QualityRunAggregator.RuleOutcome outcome(String ruleId, QualityEnforcement enforcement,
                                                     QualityIssueStatus status) {
        var issue = new StoredQualityIssue();
        issue.ruleId = ruleId;
        issue.ruleRevision = 1;
        issue.status = status;
        issue.message = "message";
        var evaluation = new QualityRuleEvaluation(status,
            status == QualityIssueStatus.PASS ? List.of() : List.of(issue), null, null);
        return new QualityRunAggregator.RuleOutcome(
            new QualityRunAggregator.AgentQualityRuleRevisionHolder(ruleId, 1, enforcement), evaluation);
    }
}


