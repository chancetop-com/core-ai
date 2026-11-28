package ai.core.server.skill.quality;

import ai.core.server.quality.QualityRuleEvaluation;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.List;

/**
 * Adapts one compiled deterministic rule to the policy-driven evaluator contract. The rule's own
 * enforcement is ignored; the active policy decides whether it blocks, warns or only reports.
 *
 * @author core-ai
 */
public final class SkillCodeRuleEvaluator implements SkillQualityRuleEvaluator {
    private final SkillQualityRule rule;

    public SkillCodeRuleEvaluator(SkillQualityRule rule) {
        this.rule = rule;
    }

    @Override
    public String evaluatorKey() {
        return rule.meta().key();
    }

    @Override
    public int version() {
        return rule.meta().revision();
    }

    @Override
    public QualityRuleEvaluation evaluate(SkillQualityRuleContext context) {
        try {
            var result = rule.evaluate(context.candidate());
            for (var issue : result.issues()) applyRevision(issue, context, result.status());
            return new QualityRuleEvaluation(result.status(), result.issues(), null, null);
        } catch (RuntimeException e) {
            return new QualityRuleEvaluation(QualityIssueStatus.ERROR,
                List.of(executionError(context)), null, "RULE_EXECUTION_ERROR");
        }
    }

    private void applyRevision(StoredQualityIssue issue, SkillQualityRuleContext context,
                               QualityIssueStatus resultStatus) {
        var revision = context.rule();
        issue.ruleId = revision.ruleKey;
        issue.ruleRevision = revision.revision;
        issue.ruleName = revision.name;
        issue.ruleRequirement = revision.description;
        issue.category = revision.category;
        issue.enforcement = resultStatus == QualityIssueStatus.PASS
            && context.enforcement() == QualityEnforcement.BLOCK
            ? QualityEnforcement.WARN : context.enforcement();
    }

    private StoredQualityIssue executionError(SkillQualityRuleContext context) {
        var revision = context.rule();
        var issue = new StoredQualityIssue();
        issue.ruleId = revision.ruleKey;
        issue.ruleRevision = revision.revision;
        issue.ruleName = revision.name;
        issue.ruleRequirement = revision.description;
        issue.status = QualityIssueStatus.ERROR;
        issue.enforcement = context.enforcement();
        issue.category = revision.category;
        issue.fieldPath = "/";
        issue.evidenceKind = QualityEvidenceKind.CODE_FACT;
        issue.issueReason = "RULE_EXECUTION_ERROR";
        issue.recommendedAction = QualityRecommendedAction.CONTACT_ADMIN;
        issue.retryable = Boolean.FALSE;
        issue.message = "Quality rule execution failed.";
        issue.suggestion = "Contact a rule administrator to inspect this rule.";
        return issue;
    }
}
