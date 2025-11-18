package ai.core.llm.domain;

/**
 * @author stephen
 */
public record CaptionImageRequest(String query, String url, String model, String systemPrompt, ResponseFormat responseFormat) {
    public static CaptionImageRequest of(String query, String url, String model, String systemPrompt, ResponseFormat responseFormat) {
        return new CaptionImageRequest(query, url, model, systemPrompt, responseFormat);
    }
    public static CaptionImageRequest of(String query, String url, String model) {
        return new CaptionImageRequest(query, url, model, null, null);
    }
}
