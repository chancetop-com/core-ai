package ai.core.server.skill.quality;

/**
 * @author core-ai
 */
public interface SkillQualityRule {
    SkillRuleMeta meta();

    SkillRuleResult evaluate(SkillPublishCandidate candidate);
}
