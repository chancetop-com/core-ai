package ai.core.cli.config;

import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A user-configured OpenAI-compatible endpoint. The profile identity is the provider type name: the
 * existing session sidecar and subagent configs address providers by type, so v1 keeps one profile per
 * type (DeepSeek, a custom OpenAI-compatible endpoint, OpenRouter).
 *
 * @author stephen
 */
public class LlmProviderProfile {
    public static LlmProviderProfile preset(LLMProviderType type) {
        var profile = new LlmProviderProfile();
        profile.id = type.getName();
        profile.type = type.getName();
        switch (type) {
            case DEEPSEEK -> {
                profile.label = "DeepSeek";
                profile.baseUrl = "https://api.deepseek.com/v1";
                profile.models = new ArrayList<>(List.of("deepseek-chat", "deepseek-reasoner"));
                profile.defaultModel = "deepseek-chat";
                profile.reasoningLevels = new ArrayList<>(List.of("low", "high", "max"));
                profile.reasoningEffortMap = new LinkedHashMap<>(Map.of("low", "low", "high", "high", "max", "xhigh"));
            }
            case OPENROUTER -> {
                profile.label = "OpenRouter";
                profile.baseUrl = "https://openrouter.ai/api/v1";
                profile.models = new ArrayList<>();
                profile.defaultModel = LLMProviders.getProviderDefaultChatModel(type);
            }
            case OPENAI -> {
                profile.label = "OpenAI-compatible";
                profile.baseUrl = "https://api.openai.com/v1";
                profile.models = new ArrayList<>();
                profile.defaultModel = LLMProviders.getProviderDefaultChatModel(type);
            }
            default -> {
                return null;
            }
        }
        return profile;
    }

    public String id;
    public String type;
    public String label;
    public String baseUrl;
    public String apiKey;
    public List<String> models = new ArrayList<>();
    public String defaultModel;
    public List<String> reasoningLevels = new ArrayList<>();
    public Map<String, String> reasoningEffortMap = new LinkedHashMap<>();

    public LLMProviderType providerType() {
        return LLMProviderType.fromName(type);
    }

    public String effectiveDefaultModel() {
        if (defaultModel != null && !defaultModel.isBlank()) return defaultModel;
        return LLMProviders.getProviderDefaultChatModel(providerType());
    }

    public boolean hasModel(String model) {
        return models != null && models.stream().anyMatch(m -> m != null && m.equals(model));
    }

    public String labelOrType() {
        return label == null || label.isBlank() ? type : label;
    }

    public String wireEffort(String level) {
        if (level == null) return null;
        if (reasoningEffortMap == null) return level;
        var mapped = reasoningEffortMap.get(level.toLowerCase(Locale.ROOT));
        return mapped == null || mapped.isBlank() ? level : mapped;
    }

    /**
     * Reverse lookup used when reading agent.properties; unknown values stay null so the caller can
     * treat them as "off".
     */
    public String internalLevel(String wire) {
        if (wire == null) return null;
        var normalized = wire.toLowerCase(Locale.ROOT);
        if (reasoningEffortMap != null) {
            for (var entry : reasoningEffortMap.entrySet()) {
                if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(normalized)) {
                    return entry.getKey();
                }
            }
        }
        return normalized;
    }
}
