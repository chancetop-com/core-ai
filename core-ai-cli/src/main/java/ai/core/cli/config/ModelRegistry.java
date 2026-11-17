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
        this(providers, props, null);
    }

    public ModelRegistry(LLMProviders providers, PropertySource props, LlmProviderStore store) {
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
        if (store != null) {
            for (var profile : store.profiles()) {
                try {
                    var type = profile.providerType();
                    if (providers.getProvider(type) == null) continue;
                    for (var model : profile.models) {
                        if (model != null && !model.isBlank()) appendIfAbsent(model.trim(), type);
                    }
                    appendIfAbsent(profile.effectiveDefaultModel(), type);
                } catch (IllegalArgumentException ignored) {
                    // unknown type in a hand-edited store
                }
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

    /** A provider-qualified lookup: direct models are allowed even when they are missing from the catalog. */
    public LLMProviderType getProviderType(String model, String provider) {
        if (provider == null || provider.isBlank()) return getProviderType(model);
        try {
            return LLMProviderType.fromName(provider);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
