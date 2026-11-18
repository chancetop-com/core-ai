package ai.core.server.skill.quality;

import ai.core.api.server.skill.SkillQualityIssueView;
import ai.core.api.server.skill.SkillQualityReportView;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.List;

/**
 * @author core-ai
 */
public final class SkillQualityReportMapper {
    public static SkillQualityReportView toView(SkillQualityReport report) {
        var view = new SkillQualityReportView();
        view.runId = report.runId;
        view.subjectType = report.subjectType;
        view.skillId = report.skillId;
        view.qualifiedName = report.qualifiedName;
        view.candidateHash = report.candidateHash;
        view.metadataHash = report.metadataHash;
        view.policyRevision = report.policyRevision;
        view.gateResult = report.gateResult == null ? null : report.gateResult.name();
        view.checksComplete = report.checksComplete;
        view.warnCount = report.warnCount;
        view.issues = issueViews(report.issues, true);
        view.rulesEvaluated = report.ruleResults == null ? 0 : report.ruleResults.size();
        view.rulesApplicable = view.rulesEvaluated;
        view.createdAt = report.createdAt;
        int blockFail = 0;
        int incomplete = 0;
        if (report.issues != null) {
            for (var issue : report.issues) {
                if (issue.enforcement == QualityEnforcement.BLOCK && issue.status == QualityIssueStatus.FAIL) {
                    blockFail++;
                }
                if (issue.enforcement == QualityEnforcement.BLOCK
                    && (issue.status == QualityIssueStatus.ERROR || issue.status == QualityIssueStatus.INDETERMINATE)) {
                    incomplete++;
                }
            }
        }
        view.blockFailCount = blockFail;
        view.incompleteCount = incomplete;
        return view;
    }

    public static List<SkillQualityIssueView> issueViews(List<StoredQualityIssue> issues, boolean includeShadow) {
        if (issues == null) return List.of();
        return issues.stream()
            .filter(issue -> includeShadow
                || issue.enforcement != QualityEnforcement.SHADOW)
            .map(SkillQualityReportMapper::issueView)
            .toList();
    }

    public static SkillQualityIssueView issueView(StoredQualityIssue issue) {
        var view = new SkillQualityIssueView();
        view.ruleId = issue.ruleId;
        view.ruleRevision = issue.ruleRevision;
        view.ruleName = issue.ruleName;
        view.ruleRequirement = issue.ruleRequirement;
        view.status = issue.status == null ? null : issue.status.name();
        view.enforcement = issue.enforcement == null ? null : issue.enforcement.name();
        view.fieldPath = issue.fieldPath;
        view.evidenceKind = issue.evidenceKind == null ? null : issue.evidenceKind.name();
        view.evidenceText = issue.evidenceText;
        view.locationPrecision = issue.locationPrecision;
        view.message = issue.message;
        view.suggestion = issue.suggestion;
        view.decisionType = issue.decisionType;
        view.questionId = issue.questionId;
        view.predicate = issue.predicate;
        view.polarity = issue.polarity;
        view.decisionMetric = issue.decisionMetric;
        view.affirmativeProbability = issue.affirmativeProbability;
        view.affirmativeThreshold = issue.affirmativeThreshold;
        view.negativeThreshold = issue.negativeThreshold;
        view.decisionMapping = issue.decisionMapping;
        view.issueReason = issue.issueReason;
        view.retryable = issue.retryable;
        view.recommendedAction = issue.recommendedAction == null ? null : issue.recommendedAction.name();
        view.category = issue.category;
        return view;
    }

    private SkillQualityReportMapper() {
    }
}
