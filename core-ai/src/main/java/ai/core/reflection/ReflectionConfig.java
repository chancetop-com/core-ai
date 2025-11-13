package ai.core.reflection;

/**
 * Configuration for agent reflection mechanism.
 *
 * @param enabled whether reflection is enabled
 * @param maxRound maximum number of reflection rounds
 * @param minRound minimum number of reflection rounds
 * @param prompt custom reflection prompt template
 * @param evaluationCriteria optional business standards/criteria for evaluation (can be null or empty)
 * @author stephen
 */
public record ReflectionConfig(boolean enabled, int maxRound, int minRound, String prompt, String evaluationCriteria) {

    /**
     * Creates a default reflection config without evaluation criteria (backward compatible).
     */
    public static ReflectionConfig defaultReflectionConfig() {
        return new ReflectionConfig(true, 3, 1, Reflection.DEFAULT_REFLECTION_CONTINUE_TEMPLATE, null);
    }

    /**
     * Creates a reflection config with evaluation criteria.
     */
    public static ReflectionConfig withEvaluationCriteria(String evaluationCriteria) {
        return new ReflectionConfig(true, 3, 1, Reflection.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE, evaluationCriteria);
    }

    /**
     * Creates a backward compatible instance without evaluation criteria.
     * @deprecated Use the constructor with evaluationCriteria parameter instead
     */
    @Deprecated
    public ReflectionConfig(boolean enabled, int maxRound, int minRound, String prompt) {
        this(enabled, maxRound, minRound, prompt, null);
    }
}
