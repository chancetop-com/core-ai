package ai.core.cli.config;

import ai.core.bootstrap.PropertySource;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of available models across all configured providers.
 * Supports same model name in different providers.
 * Not thread-safe — all access must be from the input thread.
 *
 * @author xander
 */
public class ModelRegistry {

    private final List<ModelEntry> entries = new ArrayList<>();

    public ModelRegistry(LLMProviders providers, PropertySource props) {
        for (var type : providers.getProviderTypes()) {
            String prefix = type.getName();
            props.property(prefix + ".models").ifPresent(models -> {
                for (String m : models.split(",")) {
                    String trimmed = m.trim();
                    if (!trimmed.isEmpty()) {
                        appendIfAbsent(entries, trimmed, type);
                    }
                }
            });
            var provider = providers.getProvider(type);
            if (provider != null && provider.config.getModel() != null) {
                appendIfAbsent(entries, provider.config.getModel(), type);
            }
        }
    }

    public List<ModelEntry> getAllEntries() {
        return List.copyOf(entries);
    }

    public List<String> getAllModels() {
        return entries.stream().map(ModelEntry::model).distinct().toList();
    }

    public LLMProviderType getProviderType(String model) {
        for (var entry : entries) {
            if (entry.model.equals(model)) return entry.providerType;
        }
        return null;
    }

    public void addModel(String model, LLMProviderType type) {
        appendIfAbsent(entries, model, type);
    }

    public boolean removeModel(String model, LLMProviderType type) {
        return entries.removeIf(e -> e.model.equals(model) && e.providerType == type);
    }

    private void appendIfAbsent(List<ModelEntry> list, String model, LLMProviderType type) {
        boolean exists = list.stream().anyMatch(e -> e.model.equals(model) && e.providerType == type);
        if (!exists) {
            list.add(new ModelEntry(model, type));
        }
    }

    public record ModelEntry(String model, LLMProviderType providerType) {
    }
}
