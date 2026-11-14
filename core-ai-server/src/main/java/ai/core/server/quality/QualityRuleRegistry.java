package ai.core.server.quality;

import ai.core.server.quality.domain.QualityRuleKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluator extension point. Adding a new checker is a code change; changing parameters or composing a new
 * DECISION rule through the admin UI is not. Unregistered keys are rejected at policy activation time.
 *
 * @author core-ai
 */
public class QualityRuleRegistry {
    public static List<String> inputOptions() {
        return List.of(CustomRuleInput.DESCRIPTION, CustomRuleInput.SYSTEM_PROMPT,
            CustomRuleInput.RESOURCE_SUMMARY, CustomRuleInput.CAPABILITIES);
    }

    public static boolean isKnownKind(String kind) {
        return QualityRuleKind.CODE.name().equals(kind)
            || QualityRuleKind.DECISION.name().equals(kind)
            || QualityRuleKind.HYBRID.name().equals(kind);
    }

    private final Map<String, QualityRuleEvaluator> evaluators = new LinkedHashMap<>();

    public void register(QualityRuleEvaluator evaluator) {
        evaluators.put(evaluator.evaluatorKey(), evaluator);
    }

    public QualityRuleEvaluator require(String evaluatorKey) {
        var evaluator = evaluators.get(evaluatorKey);
        if (evaluator == null) throw new IllegalArgumentException("unknown quality evaluator: " + evaluatorKey);
        return evaluator;
    }

    public List<QualityRuleEvaluator> all() {
        return List.copyOf(evaluators.values());
    }

    public List<QualityRuleEvaluator.RuleTemplate> templates() {
        return evaluators.values().stream().map(QualityRuleEvaluator::template).toList();
    }

    public boolean isDecisionComposable() {
        return evaluators.containsKey(DecisionQualityRule.EVALUATOR_KEY);
    }
}
