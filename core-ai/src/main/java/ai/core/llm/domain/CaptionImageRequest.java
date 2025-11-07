package ai.core.llm.domain;

import java.util.Map;

/**
 * @author stephen
 */
public record CaptionImageRequest(String query, String url, String model, String systemPrompt, Map<String, Object> jsonSchema) {
    public static CaptionImageRequest of(String query, String url, String model, String systemPrompt, Map<String, Object> jsonSchema) {
        return new CaptionImageRequest(query, url, model, systemPrompt, jsonSchema);
    }
    public static CaptionImageRequest of(String query, String url, String model) {
        return new CaptionImageRequest(query, url, model, null, null);
    }
}
