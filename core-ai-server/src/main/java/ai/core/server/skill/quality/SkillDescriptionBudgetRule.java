package ai.core.server.skill.quality;

import ai.core.document.Tokenizer;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

/**
 * Checks the CL100K_BASE token budget of a skill description using Tokenizer.tokenCount().
 * Thresholds vary by tier (ALWAYS_ON <= 120, CORE <= 250, DOMAIN <= 500; IMPORTED is skipped).
 * In P1 this rule only emits warnings so existing long descriptions can transition smoothly.
 *
 * @author stephen
 */
public final class SkillDescriptionBudgetRule implements SkillQualityRule {
    public static final String KEY = "SKILL.DESCRIPTION_BUDGET";
    public static final int MAX_DESCRIPTION_CHARS = 4096;
    public static final int VERBOSE_TOKENS = 120;
    public static final int ALWAYS_ON_MAX_TOKENS = 120;
    public static final int CORE_MAX_TOKENS = 250;
    public static final int DOMAIN_MAX_TOKENS = 500;
    public static final boolean BLOCK_ON_BUDGET_EXCEEDED = false;

    private static final SkillRuleMeta META = new SkillRuleMeta(
        KEY, 1, "Description token budget",
        "description token count (CL100K_BASE) should stay within the tier budget (ALWAYS_ON <= 120, CORE <= 250, DOMAIN <= 500).",
        "DISCOVERY", QualityEnforcement.WARN);

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var tier = resolveTier(candidate);
        if (tier == SkillTier.IMPORTED) return SkillRuleResult.pass();
        var description = candidate.description == null ? "" : candidate.description.trim();
        if (description.isEmpty()) return SkillRuleResult.pass();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            return exceededResult("DESCRIPTION_CHARS_EXCEEDED",
                "Description length " + description.length() + " exceeds the " + MAX_DESCRIPTION_CHARS + "-character guard.",
                "Shorten description to a concise summary within " + limitFor(tier) + " tokens.");
        }
        int tokens = Tokenizer.tokenCount(description);
        if (tokens <= VERBOSE_TOKENS) return SkillRuleResult.pass();
        int limit = limitFor(tier);
        if (tokens > limit) {
            return exceededResult(exceededReason(tier),
                "Description uses " + tokens + " tokens (CL100K_BASE), exceeding the " + tier.name()
                    + " budget of " + limit + " tokens.",
                "Trim description to <= " + limit + " tokens covering capability, Use when, and Scope.");
        }
        var issue = issue(QualityIssueStatus.PASS, "DESCRIPTION_VERBOSE",
            "Description uses " + tokens + " tokens (CL100K_BASE); recommended target is <= " + VERBOSE_TOKENS + " tokens.",
            "Keep description concise (<= " + VERBOSE_TOKENS + " tokens) to save always-loaded context.");
        return SkillRuleResult.warn(issue);
    }

    private SkillRuleResult exceededResult(String reason, String message, String suggestion) {
        if (BLOCK_ON_BUDGET_EXCEEDED) {
            return SkillRuleResult.fail(issue(QualityIssueStatus.FAIL, reason, message, suggestion));
        }
        return SkillRuleResult.warn(issue(QualityIssueStatus.PASS, reason, message, suggestion));
    }

    private SkillTier resolveTier(SkillPublishCandidate candidate) {
        if (candidate.targetTier != null) return candidate.targetTier;
        return candidate.isRepoSource() ? SkillTier.IMPORTED : SkillTier.DOMAIN;
    }

    private int limitFor(SkillTier tier) {
        return switch (tier) {
            case ALWAYS_ON -> ALWAYS_ON_MAX_TOKENS;
            case CORE -> CORE_MAX_TOKENS;
            case DOMAIN, IMPORTED -> DOMAIN_MAX_TOKENS;
        };
    }

    private String exceededReason(SkillTier tier) {
        return switch (tier) {
            case ALWAYS_ON -> "ALWAYS_ON_BUDGET_EXCEEDED";
            case CORE -> "CORE_BUDGET_EXCEEDED";
            case DOMAIN, IMPORTED -> "DOMAIN_BUDGET_EXCEEDED";
        };
    }

    private StoredQualityIssue issue(QualityIssueStatus status, String reason, String message, String suggestion) {
        return SkillQualityIssues.issue(META, status, "/description", reason, message, suggestion);
    }
}
