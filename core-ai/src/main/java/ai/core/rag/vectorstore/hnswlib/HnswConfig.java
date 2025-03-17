package ai.core.rag.vectorstore.hnswlib;

/**
 * @author stephen
 */
public record HnswConfig(int dimensions, int maxItemCount, int efConstruction, int m, String distanceFunction) {
}
