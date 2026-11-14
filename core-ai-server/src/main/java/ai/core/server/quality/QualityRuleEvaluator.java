package ai.core.server.quality;

/**
 * @author core-ai
 */
public interface QualityRuleEvaluator {
    QualityRuleEvaluation evaluate(QualityRuleContext context);

    String evaluatorKey();

    int version();

    RuleTemplate template();

    record RuleParameterSpec(String name, String type, boolean required, String description,
                             String defaultValue, java.util.List<String> options) {
    }

    record RuleTemplate(String evaluatorKey, String name, String kind, String category, String description,
                        java.util.List<RuleParameterSpec> parameters,
                        java.util.List<String> inputOptions, boolean supportsDecision) {
    }
}
