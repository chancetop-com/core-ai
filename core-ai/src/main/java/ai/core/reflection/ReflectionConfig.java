package ai.core.reflection;

import ai.core.prompt.Prompts;

public record ReflectionConfig(boolean enabled, int maxRound, int minRound, String prompt, String evaluationCriteria) {

    public static ReflectionConfig defaultReflectionConfig() {
        return new ReflectionConfig(true, 3, 1, Prompts.DEFAULT_REFLECTION_CONTINUE_TEMPLATE, null);
    }

    public static ReflectionConfig withEvaluationCriteria(String evaluationCriteria) {
        return new ReflectionConfig(true, 3, 1, Prompts.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE, evaluationCriteria);
    }
}
