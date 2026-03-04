package ai.core.cli.config;

import ai.core.bootstrap.PropertySource;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of available models across all configured providers.
 * Reads optional {prefix}.models property for multi-model support per provider.
 * Not thread-safe — all access must be from the input thread.
 *
 * @author xander
 */
public class ModelRegistry {

    private final Map<String, LLMProviderType> modelProviderMap = new LinkedHashMap<>();

    public ModelRegistry(LLMProviders providers, PropertySource props) {
        for (var type : providers.getProviderTypes()) {
            String prefix = type.getName();
            props.property(prefix + ".models").ifPresent(models -> {
                for (String m : models.split(",")) {
                    String trimmed = m.trim();
                    if (!trimmed.isEmpty()) {
                        modelProviderMap.putIfAbsent(trimmed, type);
                    }
                }
            });
            var provider = providers.getProvider(type);
            if (provider != null && provider.config.getModel() != null) {
                modelProviderMap.putIfAbsent(provider.config.getModel(), type);
            }
        }
    }

    public List<String> getAllModels() {
        return new ArrayList<>(modelProviderMap.keySet());
    }

    public LLMProviderType getProviderType(String model) {
        return modelProviderMap.get(model);
    }

    public void addModel(String model, LLMProviderType type) {
        modelProviderMap.putIfAbsent(model, type);
    }
}
