package ai.core.server.skill.quality;

import ai.core.server.quality.QualityBudget;
import ai.core.server.quality.QualityDecisionGateway;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;

/**
 * Everything one skill rule evaluation may read. The candidate is immutable, and the budget/decision
 * gateway are run-scoped so a rule cannot create its own unbounded model calls.
 *
 * @author core-ai
 */
public record SkillQualityRuleContext(SkillPublishCandidate candidate,
                                      SkillQualityRuleRevision rule,
                                      QualityEnforcement enforcement,
                                      QualityBudget budget,
                                      QualityDecisionGateway decisions) {
    public double upper() {
        return rule.affirmativeThreshold == null ? 0.80 : rule.affirmativeThreshold;
    }

    public double lower() {
        return rule.negativeThreshold == null ? 0.20 : rule.negativeThreshold;
    }
}
