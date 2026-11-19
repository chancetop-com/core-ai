package ai.core.server.quality;

import java.util.List;
import java.util.Map;

/**
 * @author core-ai
 */
public interface QualityRuleEvaluator {
    QualityRuleEvaluation evaluate(QualityRuleContext context);

    String evaluatorKey();

    int version();

    RuleTemplate template();

    record RuleParameterSpec(String name, String type, boolean required, String description,
                             String defaultValue, List<String> options) {
    }

    /**
     * A question the evaluator asks the decision model. Built-in evaluators define their questions in code;
     * exposing them read-only lets an admin see what a rule actually asks without implying they are editable.
     */
    record QuestionSpec(String name, String type, String instruction, Map<String, String> criteria) {
    }

    record RuleTemplate(String evaluatorKey, String name, String kind, String category, String description,
                        List<RuleParameterSpec> parameters,
                        List<String> inputOptions, boolean supportsDecision,
                        List<QuestionSpec> questions) {
        public RuleTemplate(String evaluatorKey, String name, String kind, String category, String description,
                            List<RuleParameterSpec> parameters, List<String> inputOptions, boolean supportsDecision) {
            this(evaluatorKey, name, kind, category, description, parameters, inputOptions, supportsDecision, List.of());
        }
    }
}
