package ai.core.reflection;

/**
 * @author stephen
 */
public record ReflectionConfig(boolean enabled, int maxRound, int minRound, String prompt) {
    public static ReflectionConfig defaultReflectionConfig() {
        return new ReflectionConfig(true, 3, 1, Reflection.DEFAULT_REFLECTION_CONTINUE_TEMPLATE);
    }
}
