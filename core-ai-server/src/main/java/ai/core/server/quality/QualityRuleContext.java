package ai.core.server.quality;

import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;

/**
 * @author core-ai
 */
public record QualityRuleContext(PublishQualitySnapshot snapshot,
                                 AgentQualityRuleRevision rule,
                                 QualityEnforcement enforcement,
                                 QualityBudget budget,
                                 QualityDecisionGateway decisions) {
    public String fieldPath() {
        return snapshot.candidate().promptField();
    }

    public String systemPrompt() {
        return snapshot.candidate().prompt().systemPrompt();
    }

    public boolean usesDecision() {
        return decisions != null;
    }

    public double upper() {
        return rule.affirmativeThreshold == null ? 0.80 : rule.affirmativeThreshold;
    }

    public double lower() {
        return rule.negativeThreshold == null ? 0.20 : rule.negativeThreshold;
    }
}
