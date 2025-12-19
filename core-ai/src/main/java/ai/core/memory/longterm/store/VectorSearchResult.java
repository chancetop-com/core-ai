package ai.core.memory.longterm.store;

/**
 * Vector search result with memory ID and similarity score.
 *
 * @author xander
 */
public record VectorSearchResult(String id, double similarity) {
}
