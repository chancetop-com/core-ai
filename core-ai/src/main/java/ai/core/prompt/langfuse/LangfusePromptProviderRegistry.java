package ai.core.prompt.langfuse;

/**
 * Global registry for Langfuse prompt provider
 * Similar to TracerRegistry for telemetry
 *
 * @author stephen
 */
public final class LangfusePromptProviderRegistry {
    private static LangfusePromptProvider provider;

    public static void setProvider(LangfusePromptProvider promptProvider) {
        provider = promptProvider;
    }

    public static LangfusePromptProvider getProvider() {
        return provider;
    }

    public static boolean isConfigured() {
        return provider != null;
    }

    public static void clear() {
        provider = null;
    }

    private LangfusePromptProviderRegistry() {
    }
}
