package ai.core.cli.config;

import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.LLMProviderFactory;
import ai.core.bootstrap.PropertySource;
import ai.core.cli.auth.AuthConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.telemetry.LLMTracer;

/**
 * Applies the user-level provider store on top of the legacy properties-based bootstrap. The store wins
 * for a provider type; legacy {@code <type>.api.key} remains the fallback when no profile exists.
 *
 * @author stephen
 */
public final class ProviderWiring {
    public static void apply(LlmProviderStore store, BootstrapResult result, PropertySource props) {
        applyProfiles(store, result.llmProviders, props, result.llmTracer);
        if (result.liteLLMProvider != null && store.serverModel() != null && !store.serverModel().isBlank()) {
            result.liteLLMProvider.config.setModel(store.serverModel().trim());
        }
        refreshAliases(result);
    }

    public static void applyProfiles(LlmProviderStore store, LLMProviders providers, PropertySource props,
                                     LLMTracer tracer) {
        for (var profile : store.profiles()) {
            LLMProviderType type;
            try {
                type = profile.providerType();
            } catch (IllegalArgumentException e) {
                continue;
            }
            var base = LLMProviderFactory.baseConfig(props);
            base.removeReasoningEffort();
            var config = LLMProviderFactory.providerConfig(props, base, type.getName());
            config.mapReasoningEffort(profile::wireEffort);
            config.setModel(profileModel(profile, props));
            config.setMultiModalModel(null);
            var provider = new LiteLLMProvider(config, profile.baseUrl.trim(),
                    profile.apiKey == null ? "" : profile.apiKey);
            if (tracer != null) provider.setTracer(tracer);
            providers.addProvider(type, provider);
        }
    }

    private static String profileModel(LlmProviderProfile profile, PropertySource props) {
        if (profile.defaultModel != null && !profile.defaultModel.isBlank()) return profile.defaultModel.trim();
        var configured = props.property(profile.type + ".model").orElse(null);
        if (configured != null && !configured.isBlank()) return configured.trim();
        return LLMProviders.getProviderDefaultChatModel(profile.providerType());
    }

    public static void applyDefault(LlmProviderStore store, LLMProviders providers, PropertySource props) {
        var type = resolveDefaultType(store, providers, props, signedIn());
        if (type != null && providers.getProvider(type) != null) {
            providers.setDefaultProvider(type);
        }
    }

    /**
     * {@code store.default} first, then the legacy {@code active.provider}, then the first direct profile,
     * then the server proxy when signed in, then whatever the EnumMap yields.
     */
    public static LLMProviderType resolveDefaultType(LlmProviderStore store, LLMProviders providers,
                                                     PropertySource props, boolean signedIn) {
        var configured = configuredType(store, providers, store.defaultConnection());
        if (configured != null) return configured;
        var legacy = legacyType(props, providers);
        if (legacy != null) return legacy;
        for (var profile : store.profiles()) {
            try {
                var type = profile.providerType();
                if (providers.getProvider(type) != null) return type;
            } catch (IllegalArgumentException ignored) {
                // hand-edited file with an unknown type: skip it
            }
        }
        if (signedIn && providers.getProvider(LLMProviderType.LITELLM) != null) return LLMProviderType.LITELLM;
        return providers.getProviderTypes().stream().findFirst().orElse(null);
    }

    private static LLMProviderType configuredType(LlmProviderStore store, LLMProviders providers, String configured) {
        if (configured == null || configured.isBlank()) return null;
        if ("server".equalsIgnoreCase(configured)) {
            return providers.getProvider(LLMProviderType.LITELLM) != null ? LLMProviderType.LITELLM : null;
        }
        var profile = store.profile(configured);
        if (profile.isEmpty()) return null;
        var type = profile.get().providerType();
        return providers.getProvider(type) != null ? type : null;
    }

    private static LLMProviderType legacyType(PropertySource props, LLMProviders providers) {
        var legacy = props.property("active.provider").orElse(null);
        if (legacy == null || legacy.isBlank()) return null;
        try {
            var type = LLMProviderType.fromName(legacy);
            return providers.getProvider(type) != null ? type : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean signedIn() {
        var auth = AuthConfig.load();
        return auth != null && auth.apiKey() != null;
    }

    private static void refreshAliases(BootstrapResult result) {
        result.deepSeekProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.DEEPSEEK);
        result.openAIProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.OPENAI);
        result.openRouterProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.OPENROUTER);
    }

    private ProviderWiring() {
    }
}
