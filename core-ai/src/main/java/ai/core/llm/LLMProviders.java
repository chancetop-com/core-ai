package ai.core.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class LLMProviders {

    public static String getProviderDefaultChatModel(LLMProviderType type) {
        return switch (type) {
            case AZURE_INFERENCE -> "o1-mini";
            case DEEPSEEK -> "deepseek-chat";
            case OPENAI, AZURE -> "gpt-4o";
            default -> "gpt-3.5-turbo";
        };
    }

    Map<LLMProviderType, LLMProvider> providers = new HashMap<>();
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
        return providers.values().iterator().next();
    }

    public LLMProvider getProviderByName(String name) {
        return providers.get(LLMProviderType.fromName(name));
    }

    public List<LLMProviderType> getProviderTypes() {
        return List.copyOf(providers.keySet());
    }

    public LLMProvider getDefaultProvider() {
        return providers.get(defaultProviderType);
    }

    public void setDefaultProvider(LLMProviderType type) {
        this.defaultProviderType = type;
    }
}
