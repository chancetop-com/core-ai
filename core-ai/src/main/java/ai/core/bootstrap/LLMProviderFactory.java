package ai.core.bootstrap;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;

/**
 * Builds the configured chat providers without telemetry, persistence, vector stores or MCP servers, for
 * callers that only need a completion (e.g. a CLI tool invoked outside an agent turn).
 *
 * @author stephen
 */
public final class LLMProviderFactory {
    public static LLMProviders create(PropertySource props) {
        var providers = new LLMProviders();
        var config = setupLLMProperties(props);

        // LiteLLM
        props.property("litellm.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(props, config, "litellm");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("litellm.api.base"), props.property("litellm.api.key").orElse(""));
            providers.addProvider(LLMProviderType.LITELLM, provider);
        });

        // OpenRouter
        props.property("openrouter.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(props, config, "openrouter");
            var provider = new LiteLLMProvider(providerConfig, "https://openrouter.ai/api/v1", key);
            providers.addProvider(LLMProviderType.OPENROUTER, provider);
        });

        // DeepSeek
        props.property("deepseek.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(props, config, "deepseek");
            var provider = new LiteLLMProvider(providerConfig, "https://api.deepseek.com/v1", key);
            providers.addProvider(LLMProviderType.DEEPSEEK, provider);
        });

        // Azure OpenAI
        props.property("azure.api.key").ifPresent(key -> props.property("azure.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(props, config, "azure");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("azure.api.base"), key);
            providers.addProvider(LLMProviderType.AZURE, provider);
        }));

        // OpenAI
        props.property("openai.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(props, config, "openai");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("openai.api.base"), key);
            providers.addProvider(LLMProviderType.OPENAI, provider);
        });
        return providers;
    }

    private static LLMProviderConfig setupLLMProperties(PropertySource props) {
        var config = new LLMProviderConfig(null, 0.7d, "text-embedding-3-large");
        applyConfigProperties(props, config, "llm");
        if (config.getModel() == null) {
            config.setModel("deepseek/deepseek-v4-flash");
        }
        if (config.getMultiModalModel() == null) {
            config.setMultiModalModel("azure/responses/gpt-5-mini");
        }
        return config;
    }

    private static LLMProviderConfig createProviderConfig(PropertySource props, LLMProviderConfig baseConfig, String prefix) {
        var config = new LLMProviderConfig(baseConfig);
        applyConfigProperties(props, config, prefix);
        return config;
    }

    private static void applyConfigProperties(PropertySource props, LLMProviderConfig config, String prefix) {
        props.property(prefix + ".model").ifPresent(config::setModel);
        props.property(prefix + ".model.multimodal").ifPresent(config::setMultiModalModel);
        props.property(prefix + ".temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        props.property(prefix + ".embeddings.model").ifPresent(config::setEmbeddingModel);
        props.property(prefix + ".request.extra_body").ifPresent(config::setRequestExtraBody);
        props.property(prefix + ".timeout.seconds").ifPresent(v -> config.setTimeout(Long.valueOf(v)));
        props.property(prefix + ".connect.timeout.seconds").ifPresent(v -> config.setConnectTimeout(Long.valueOf(v)));
        props.property(prefix + ".stream.buffer.size").ifPresent(v -> config.setStreamBufferSize(Integer.parseInt(v)));
        applyModelExtraBodies(props, config, prefix);
    }

    private static void applyModelExtraBodies(PropertySource props, LLMProviderConfig config, String prefix) {
        var extraBodyPrefix = prefix + ".request.extra_body.";
        for (var key : props.propertyNames()) {
            if (key.startsWith(extraBodyPrefix) && key.length() > extraBodyPrefix.length()) {
                var modelName = key.substring(extraBodyPrefix.length());
                props.property(key).ifPresent(extraBody -> config.addModelExtraBody(modelName, extraBody));
            }
        }
    }

    private LLMProviderFactory() {
    }
}
