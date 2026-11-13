package ai.core.cli.config;

import ai.core.bootstrap.PropertySource;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of available models across all configured providers.
 * Supports same model name in different providers.
 * Safe for concurrent reads; mutations are serialized.
 *
 * @author xander
 */
public class ModelRegistry {

    private final List<ModelEntry> entries = new CopyOnWriteArrayList<>();

    public ModelRegistry(LLMProviders providers, PropertySource props) {
        for (var type : providers.getProviderTypes()) {
            String prefix = type.getName();
            props.property(prefix + ".models").ifPresent(models -> {
                for (String m : models.split(",")) {
                    String trimmed = m.trim();
                    if (!trimmed.isEmpty()) {
                        appendIfAbsent(trimmed, type);
                    }
                }
            });
            var provider = providers.getProvider(type);
            if (provider != null && provider.config.getModel() != null) {
                appendIfAbsent(provider.config.getModel(), type);
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
        appendIfAbsent(model, type);
    }

    public boolean removeModel(String model, LLMProviderType type) {
        return entries.removeIf(e -> e.providerType == type && e.model.equals(model));
    }

    private void appendIfAbsent(String model, LLMProviderType type) {
        synchronized (this) {
            boolean exists = entries.stream().anyMatch(e -> e.providerType == type && e.model.equals(model));
            if (!exists) {
                entries.add(new ModelEntry(model, type));
            }
        }
    }

    public record ModelEntry(String model, LLMProviderType providerType) {
    }
}
