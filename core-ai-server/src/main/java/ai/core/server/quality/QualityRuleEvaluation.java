package ai.core.server.quality;

import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.List;

/**
 * @author core-ai
 */
public record QualityRuleEvaluation(QualityIssueStatus status,
                                    List<StoredQualityIssue> issues,
                                    String routeFingerprint,
                                    String errorReason) {
    public QualityRuleEvaluation {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static QualityRuleEvaluation pass(String routeFingerprint) {
        return new QualityRuleEvaluation(QualityIssueStatus.PASS, List.of(), routeFingerprint, null);
    }

    public static QualityRuleEvaluation fail(StoredQualityIssue issue, String routeFingerprint) {
        return new QualityRuleEvaluation(QualityIssueStatus.FAIL, List.of(issue), routeFingerprint, null);
    }

    public static QualityRuleEvaluation issue(QualityIssueStatus status, List<StoredQualityIssue> issues,
                                              String routeFingerprint, String errorReason) {
        return new QualityRuleEvaluation(status, issues, routeFingerprint, errorReason);
    }
}
