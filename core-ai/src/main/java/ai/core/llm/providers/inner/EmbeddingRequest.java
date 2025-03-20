package ai.core.llm.providers.inner;

import java.util.List;

/**
 * @author stephen
 */
public record EmbeddingRequest(List<String> query) {
}
