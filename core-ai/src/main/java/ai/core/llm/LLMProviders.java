package ai.core.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class LLMProviders {

    public static String getProviderDefaultChatModel(LLMProviderType type) {
        return switch (type) {
            case AZURE_INFERENCE -> "gpt-5-mini";
            case DEEPSEEK -> "deepseek-chat";
            default -> "gpt-4o";
        };
    }

    Map<LLMProviderType, LLMProvider> providers = new EnumMap<>(LLMProviderType.class);
    LLMProviderType defaultProviderType;

    public void addProvider(LLMProviderType type, LLMProvider provider) {
        providers.put(type, provider);
    }

    public LLMProvider getProvider(LLMProviderType type) {
        return providers.get(type);
    }

    public LLMProvider getProvider() {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No LLM providers configured");
        }
        return providers.get(getProviderDefaultModel());
    }

    public LLMProviderType getProviderDefaultModel() {
        if (providers.isEmpty()) {
            throw new IllegalStateException("No LLM providers configured");
        }
        return providers.keySet().iterator().next();
    }

    public LLMProvider getProviderByName(String name) {
        return providers.get(LLMProviderType.fromName(name));
    }

    public List<LLMProviderType> getProviderTypes() {
        return List.copyOf(providers.keySet());
    }

    public LLMProvider getDefaultProvider() {
        if (defaultProviderType == null) return getProvider();
        return providers.get(defaultProviderType);
    }

    public void setDefaultProvider(LLMProviderType type) {
        this.defaultProviderType = type;
    }
}
