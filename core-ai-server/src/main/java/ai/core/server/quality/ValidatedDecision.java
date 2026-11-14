package ai.core.server.quality;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRoute;

import java.util.List;
import java.util.Map;

/**
 * Validated answers of one decision call. Only answers that passed the strict contract reach a rule.
 *
 * @author core-ai
 */
public record ValidatedDecision(Map<String, DecisionAnswer> answers,
                                List<DecisionQuestion> questions,
                                DecisionRoute route,
                                String upstreamModel,
                                long inputTokens,
                                long outputTokens) {
    public DecisionAnswer require(String question) {
        var answer = answers.get(question);
        if (answer == null) throw new QualityDecisionException(QualityDecisionException.Kind.PROTOCOL,
            "decision response is missing answer: " + question, false, null);
        return answer;
    }
}
