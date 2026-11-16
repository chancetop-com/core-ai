package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

/**
 * Skill-specific issue builder. It writes the rule identity directly instead of borrowing the Agent rule
 * entity, while the stored issue and aggregator stay shared.
 *
 * @author core-ai
 */
public final class SkillQualityIssues {
    public static StoredQualityIssue issue(SkillRuleMeta meta, QualityIssueStatus status, String fieldPath,
                                           String reason, String message, String suggestion) {
        var issue = new StoredQualityIssue();
        issue.ruleId = meta.key();
        issue.ruleRevision = meta.revision();
        issue.ruleName = meta.name();
        issue.ruleRequirement = meta.requirement();
        issue.status = status;
        issue.enforcement = meta.enforcement();
        issue.fieldPath = fieldPath;
        issue.evidenceKind = QualityEvidenceKind.CODE_FACT;
        issue.issueReason = reason;
        issue.recommendedAction = QualityRecommendedAction.EDIT_INPUT;
        issue.retryable = Boolean.FALSE;
        issue.category = meta.category();
        issue.message = message;
        issue.suggestion = suggestion;
        return issue;
    }

    private SkillQualityIssues() {
    }
}
