package ai.core.server.skill.quality;

import ai.core.server.quality.QualityIssues;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;

import java.util.Map;

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

    @SuppressWarnings("checkstyle:ParameterNumber")
    public static StoredQualityIssue fromRevision(SkillQualityRuleRevision rule, QualityEnforcement enforcement,
                                                  QualityIssueStatus status, String fieldPath,
                                                  QualityEvidenceKind evidenceKind, String reason,
                                                  QualityRecommendedAction action, boolean retryable,
                                                  String fallbackMessage, String fallbackSuggestion) {
        var issue = new StoredQualityIssue();
        issue.ruleId = rule.ruleKey != null ? rule.ruleKey : rule.ruleId;
        issue.ruleRevision = rule.revision;
        issue.ruleName = rule.name;
        issue.ruleRequirement = rule.description;
        issue.status = status;
        issue.enforcement = enforcement;
        issue.fieldPath = fieldPath;
        issue.evidenceKind = evidenceKind;
        issue.issueReason = reason;
        issue.recommendedAction = action;
        issue.retryable = retryable;
        issue.category = rule.category;
        issue.message = QualityIssues.render(rule.messageTemplate, Map.of(), fallbackMessage);
        issue.suggestion = QualityIssues.render(rule.remediationTemplate, Map.of(), fallbackSuggestion);
        return issue;
    }

    private SkillQualityIssues() {
    }
}
