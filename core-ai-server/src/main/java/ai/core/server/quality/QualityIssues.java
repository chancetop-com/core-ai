package ai.core.server.quality;

import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.Map;

/**
 * Builds issues from the immutable rule definition. Templates may only reference declared values;
 * unresolved placeholders stay visible instead of being evaluated as expressions.
 *
 * @author core-ai
 */
public final class QualityIssues {
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static StoredQualityIssue of(AgentQualityRuleRevision rule, QualityEnforcement enforcement,
                                        QualityIssueStatus status, String fieldPath,
                                        QualityEvidenceKind evidenceKind, String reason,
                                        QualityRecommendedAction action, boolean retryable,
                                        Map<String, String> variables, String fallbackMessage,
                                        String fallbackSuggestion) {
        var issue = new StoredQualityIssue();
        issue.ruleId = rule.ruleId;
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
        issue.message = render(rule.messageTemplate, variables, fallbackMessage);
        issue.suggestion = render(rule.remediationTemplate, variables, fallbackSuggestion);
        return issue;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
    public static StoredQualityIssue copyMetadata(AgentQualityRuleRevision rule, QualityEnforcement enforcement,
                                                  StoredQualityIssue issue) {
        if (issue == null) return null;
        issue.ruleId = rule.ruleId;
        issue.ruleRevision = rule.revision;
        issue.ruleName = rule.name;
        issue.ruleRequirement = rule.description;
        issue.enforcement = enforcement;
        if (issue.category == null) issue.category = rule.category;
        if (issue.message == null) issue.message = rule.messageTemplate;
        if (issue.suggestion == null) issue.suggestion = rule.remediationTemplate;
        return issue;
    }

    public static String render(String template, Map<String, String> variables, String fallback) {
        if (template == null || template.isBlank()) return fallback;
        var result = template;
        if (variables != null) {
            for (var entry : variables.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return result;
    }

    private QualityIssues() {
    }
}
