package ai.core.server.skill.quality;

import ai.core.server.quality.QualityRuleEvaluator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author core-ai
 */
public class SkillQualityRuleRegistry {
    public static List<String> inputOptions() {
        return List.of(SkillCustomRuleInput.DESCRIPTION, SkillCustomRuleInput.SKILL_CONTENT,
            SkillCustomRuleInput.METADATA, SkillCustomRuleInput.ALLOWED_TOOLS,
            SkillCustomRuleInput.RESOURCE_MANIFEST, SkillCustomRuleInput.EVAL_DATASET);
    }

    private final Map<String, SkillQualityRuleEvaluator> evaluators = new LinkedHashMap<>();

    public void register(SkillQualityRuleEvaluator evaluator) {
        evaluators.put(evaluator.evaluatorKey(), evaluator);
    }

    public SkillQualityRuleEvaluator require(String evaluatorKey) {
        var evaluator = evaluators.get(evaluatorKey);
        if (evaluator == null) throw new IllegalArgumentException("unknown skill quality evaluator: " + evaluatorKey);
        return evaluator;
    }

    public List<SkillQualityRuleEvaluator> all() {
        return List.copyOf(evaluators.values());
    }

    public List<QualityRuleEvaluator.RuleTemplate> templates() {
        return List.of(SkillDecisionQualityRule.template());
    }

    public boolean isDecisionComposable() {
        return evaluators.containsKey(SkillDecisionQualityRule.EVALUATOR_KEY);
    }
}
