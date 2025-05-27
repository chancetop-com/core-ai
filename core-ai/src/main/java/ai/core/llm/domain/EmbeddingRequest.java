package ai.core.llm.domain;

import java.util.List;

/**
 * @author stephen
 */
public record EmbeddingRequest(List<String> query) {
}
