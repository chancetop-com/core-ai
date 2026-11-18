package ai.core.server.skill.quality;

import ai.core.server.quality.QualityRuleEvaluation;

/**
 * Registered skill rule implementation. New evaluators are a code change; composing a DECISION rule
 * through the admin UI is not.
 *
 * @author core-ai
 */
public interface SkillQualityRuleEvaluator {
    String evaluatorKey();

    int version();

    QualityRuleEvaluation evaluate(SkillQualityRuleContext context);
}
