package ai.core.server.skill.quality;

import ai.core.server.quality.QualityBudget;
import ai.core.server.quality.QualityDecisionGateway;
import ai.core.server.quality.QualityExemptions;
import ai.core.server.quality.QualityHashes;
import ai.core.server.quality.QualityRuleEvaluation;
import ai.core.server.quality.QualityRunAggregator;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import core.framework.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the active skill policy against one candidate. Built-in deterministic rules are compiled code;
 * admin-composed DECISION rules are data. Both run under the same immutable revision and the same
 * run-scoped budget, so a custom rule cannot silently raise its own cost ceiling.
 *
 * @author core-ai
 */
public class SkillQualityGate {
    /** Used only by unit tests and by the pre-policy fallback; persisted runs use the active policy revision. */
    public static final int FALLBACK_POLICY_REVISION = 1;
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private static final List<SkillQualityRule> BUILT_IN_RULES = List.of(
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

    public static List<SkillQualityRule> builtInRules() {
        return BUILT_IN_RULES;
    }

    public static List<SkillRuleMeta> ruleCatalog() {
        return BUILT_IN_RULES.stream().map(SkillQualityRule::meta).toList();
    }

    public static List<String> ruleKeys() {
        return ruleCatalog().stream().map(SkillRuleMeta::key).toList();
    }

    @Inject
    SkillQualityPolicyService policyService;
    @Inject
    SkillQualityRuleRegistry registry;
    private QualityDecisionGateway decisions;

    /** Wired after QualityGateModule has bound the shared decision gateway. */
    public void setDecisions(QualityDecisionGateway decisions) {
        this.decisions = decisions;
    }

    /**
     * Third-party repo skills are authored outside the platform, so the publish policy covers only skills we author:
     * a repo import is recorded as exempted with its own reason instead of running the rules.
     */
    public SkillQualityResult evaluate(SkillPublishCandidate candidate) {
        if (policyService == null || registry == null) {
            return candidate.isRepoSource()
                ? exemptResult(FALLBACK_POLICY_REVISION, QualityExemptions.THIRD_PARTY_REPO_REASON)
                : evaluateStatic(candidate);
        }
        var policy = policyService.active();
        if (candidate.isRepoSource()) return exemptResult(policy.revision(), QualityExemptions.THIRD_PARTY_REPO_REASON);
        var matched = QualityExemptions.match(policy.exemptions(), candidate.qualifiedName(), candidate.name);
        if (matched != null) return exemptResult(policy.revision(), QualityExemptions.WHITELIST_REASON + ": " + matched);
        var budget = QualityBudget.forDeadline(DEADLINE);
        var outcomes = new ArrayList<QualityRunAggregator.RuleOutcome>();
        for (var active : policy.rules()) {
            outcomes.add(evaluateRevision(active.revision(), active.enforcement(), candidate, budget));
        }
        return result(QualityRunAggregator.aggregate(outcomes), policy.revision());
    }

    public QualityRuleEvaluation testRule(SkillPublishCandidate candidate, SkillQualityRuleRevision revision,
                                          QualityEnforcement enforcement) {
        if (registry == null) throw new IllegalStateException("skill quality registry is not initialized");
        return evaluateRevision(revision, enforcement, candidate, QualityBudget.forDeadline(DEADLINE)).evaluation();
    }

    private SkillQualityResult evaluateStatic(SkillPublishCandidate candidate) {
        var outcomes = new ArrayList<QualityRunAggregator.RuleOutcome>();
        for (var rule : BUILT_IN_RULES) {
            outcomes.add(evaluateBuiltIn(rule, candidate));
        }
        return result(QualityRunAggregator.aggregate(outcomes), FALLBACK_POLICY_REVISION);
    }

    private QualityRunAggregator.RuleOutcome evaluateBuiltIn(SkillQualityRule rule, SkillPublishCandidate candidate) {
        var meta = rule.meta();
        var holder = new QualityRunAggregator.AgentQualityRuleRevisionHolder(meta.key(), meta.revision(),
            meta.enforcement());
        try {
            var result = rule.evaluate(candidate);
            return new QualityRunAggregator.RuleOutcome(holder,
                new QualityRuleEvaluation(result.status(), result.issues(), null, null));
        } catch (RuntimeException e) {
            return new QualityRunAggregator.RuleOutcome(holder, executionError(meta));
        }
    }

    private QualityRunAggregator.RuleOutcome evaluateRevision(SkillQualityRuleRevision revision,
                                                              QualityEnforcement enforcement,
                                                              SkillPublishCandidate candidate, QualityBudget budget) {
        var holder = new QualityRunAggregator.AgentQualityRuleRevisionHolder(revision.ruleKey, revision.revision,
            enforcement);
        try {
            var evaluator = registry.require(revision.evaluatorKey);
            var evaluation = evaluator.evaluate(new SkillQualityRuleContext(candidate, revision, enforcement, budget,
                decisions));
            return new QualityRunAggregator.RuleOutcome(holder, evaluation);
        } catch (RuntimeException e) {
            return new QualityRunAggregator.RuleOutcome(holder, executionError(revision, enforcement));
        }
    }

    private QualityRuleEvaluation executionError(SkillRuleMeta meta) {
        var issue = new StoredQualityIssue();
        issue.ruleId = meta.key();
        issue.ruleRevision = meta.revision();
        issue.ruleName = meta.name();
        issue.ruleRequirement = meta.requirement();
        issue.status = QualityIssueStatus.ERROR;
        issue.enforcement = meta.enforcement();
        issue.fieldPath = "/";
        issue.evidenceKind = QualityEvidenceKind.CODE_FACT;
        issue.issueReason = "RULE_EXECUTION_ERROR";
        issue.recommendedAction = QualityRecommendedAction.CONTACT_ADMIN;
        issue.retryable = Boolean.FALSE;
        issue.category = meta.category();
        issue.message = "Quality rule execution failed.";
        issue.suggestion = "Contact a rule administrator to inspect this rule.";
        return new QualityRuleEvaluation(QualityIssueStatus.ERROR, List.of(issue), null, "RULE_EXECUTION_ERROR");
    }

    private QualityRuleEvaluation executionError(SkillQualityRuleRevision revision, QualityEnforcement enforcement) {
        var issue = SkillQualityIssues.fromRevision(revision, enforcement, QualityIssueStatus.ERROR, "/",
            QualityEvidenceKind.CODE_FACT, "RULE_EXECUTION_ERROR", QualityRecommendedAction.CONTACT_ADMIN, false,
            "Quality rule execution failed.", "Contact a rule administrator to inspect this rule.");
        return new QualityRuleEvaluation(QualityIssueStatus.ERROR, List.of(issue), null, "RULE_EXECUTION_ERROR");
    }

    private SkillQualityResult exemptResult(int policyRevision, String reason) {
        return new SkillQualityResult(UUID.randomUUID().toString(), ai.core.server.quality.domain.QualityGateResult.PASSED,
            true, List.of(), List.of(), null, QualityHashes.issueDigest(List.of()), 0, policyRevision, true, reason);
    }

    private SkillQualityResult result(QualityRunAggregator.Aggregated aggregated, int policyRevision) {
        var warnCount = 0;
        for (var issue : aggregated.issues()) {
            if (issue.enforcement == QualityEnforcement.WARN) warnCount++;
        }
        return new SkillQualityResult(UUID.randomUUID().toString(), aggregated.gateResult(),
            aggregated.checksComplete(), aggregated.issues(), aggregated.ruleResults(),
            aggregated.routeFingerprint(), aggregated.ruleResultDigest(), warnCount, policyRevision, false, null);
    }
}
