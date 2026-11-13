package ai.core.cli.agent;

import ai.core.agent.subagent.SubagentModelResolver;
import ai.core.agent.subagent.SubagentModelUnavailableException;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.config.ModelRegistry;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.List;

/**
 * Resolves a subagent model against the same provider table the /model picker uses: the local
 * registry first, then the LiteLLM gateway for models only the server knows about.
 *
 * @author stephen
 */
public class CliSubagentModelResolver implements SubagentModelResolver {
    public static CliSubagentModelResolver create(PropertiesFileSource props, LLMProviders providers) {
        return new CliSubagentModelResolver(new ModelRegistry(providers, props), providers);
    }

    private final ModelRegistry modelRegistry;
    private final LLMProviders providers;

    public CliSubagentModelResolver(ModelRegistry modelRegistry, LLMProviders providers) {
        this.modelRegistry = modelRegistry;
        this.providers = providers;
    }

    @Override
    public ResolvedModel resolve(String model, String preferredProvider) {
        var preferredType = providerType(preferredProvider);
        if (preferredType != null) {
            var preferred = providers.getProvider(preferredType);
            if (preferred != null && canServe(preferredType, model)) {
                return new ResolvedModel(model, preferred, preferredType.getName());
            }
        }

        var type = modelRegistry.getProviderType(model);
        if (type == null && providers.getProvider(LLMProviderType.LITELLM) != null) {
            type = LLMProviderType.LITELLM;
        }
        if (type == null) {
            throw new SubagentModelUnavailableException(
                    SubagentModelUnavailableException.Reason.UNKNOWN_MODEL, model);
        }
        var provider = providers.getProvider(type);
        if (provider == null) {
            throw new SubagentModelUnavailableException(
                    SubagentModelUnavailableException.Reason.PROVIDER_UNAVAILABLE, model, type.getName());
        }
        return new ResolvedModel(model, provider, type.getName());
    }

    @Override
    public List<String> availableModels() {
        return modelRegistry.getAllModels();
    }

    private LLMProviderType providerType(String name) {
        if (name == null || name.isBlank()) return null;
        for (var type : providers.getProviderTypes()) {
            if (type.getName().equalsIgnoreCase(name)) return type;
        }
        return null;
    }

    private boolean canServe(LLMProviderType type, String model) {
        // the gateway routes model ids it knows about; local providers only serve their registry entries
        return type == LLMProviderType.LITELLM || type == modelRegistry.getProviderType(model);
    }
}
