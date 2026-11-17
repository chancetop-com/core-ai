package ai.core.server.skill.quality;

import ai.core.server.quality.QualityRunAggregator;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the built-in skill rules against one candidate. The rule list is versioned with the code; changing a
 * threshold or adding a rule changes this class and therefore needs a release, which is the honest cost of a
 * deterministic gate that cannot be edited into a bypass at runtime.
 *
 * @author core-ai
 */
public final class SkillQualityGate {
    public static final int POLICY_REVISION = 1;

    private static final List<SkillQualityRule> RULES = List.of(
        new SkillNameIdentityRule(),
        new SkillDescriptionRule(),
        new SkillMetadataRule(),
        new SkillPackageHygieneRule(),
        new SkillDisclosureRule(),
        new SkillEvalRule(),
        new SkillPermissionRule(),
        new SkillPermissionUnusedRule(),
        new SkillVersionWarningRule(),
        new SkillUnreferencedReferenceRule()
    );

    public static List<SkillRuleMeta> ruleCatalog() {
        return RULES.stream().map(SkillQualityRule::meta).toList();
    }

    public static List<String> ruleKeys() {
        return ruleCatalog().stream().map(SkillRuleMeta::key).toList();
    }

    public SkillQualityResult evaluate(SkillPublishCandidate candidate) {
        var outcomes = new ArrayList<QualityRunAggregator.RuleOutcome>();
        for (var rule : RULES) {
            outcomes.add(evaluate(rule, candidate));
        }
        var aggregated = QualityRunAggregator.aggregate(outcomes);
        var warnCount = 0;
        for (var issue : aggregated.issues()) {
            if (issue.enforcement == QualityEnforcement.WARN) warnCount++;
        }
        return new SkillQualityResult(UUID.randomUUID().toString(), aggregated.gateResult(),
            aggregated.checksComplete(), aggregated.issues(), aggregated.ruleResults(),
            aggregated.routeFingerprint(), aggregated.ruleResultDigest(), warnCount);
    }

    private QualityRunAggregator.RuleOutcome evaluate(SkillQualityRule rule, SkillPublishCandidate candidate) {
        var holder = new QualityRunAggregator.AgentQualityRuleRevisionHolder(
            rule.meta().key(), rule.meta().revision(), rule.meta().enforcement());
        try {
            return new QualityRunAggregator.RuleOutcome(holder, from(rule.evaluate(candidate)));
        } catch (RuntimeException e) {
            var issue = new StoredQualityIssue();
            issue.ruleId = rule.meta().key();
            issue.ruleRevision = rule.meta().revision();
            issue.ruleName = rule.meta().name();
            issue.ruleRequirement = rule.meta().requirement();
            issue.status = QualityIssueStatus.ERROR;
            issue.enforcement = rule.meta().enforcement();
            issue.fieldPath = "/";
            issue.evidenceKind = QualityEvidenceKind.CODE_FACT;
            issue.issueReason = "RULE_EXECUTION_ERROR";
            issue.recommendedAction = QualityRecommendedAction.CONTACT_ADMIN;
            issue.retryable = Boolean.FALSE;
            issue.category = rule.meta().category();
            issue.message = "质量规则执行失败。";
            issue.suggestion = "联系规则管理员检查该规则。";
            return new QualityRunAggregator.RuleOutcome(holder,
                new ai.core.server.quality.QualityRuleEvaluation(QualityIssueStatus.ERROR,
                    List.of(issue), null, "RULE_EXECUTION_ERROR"));
        }
    }

    private ai.core.server.quality.QualityRuleEvaluation from(SkillRuleResult result) {
        return new ai.core.server.quality.QualityRuleEvaluation(result.status(), result.issues(), null, null);
    }
}
