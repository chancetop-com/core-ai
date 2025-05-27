package ai.core.llm.domain;

/**
 * @author stephen
 */
public record CaptionImageRequest(String query, String url, String model) {
}
