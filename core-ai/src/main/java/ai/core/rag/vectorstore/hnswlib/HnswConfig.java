package ai.core.rag.vectorstore.hnswlib;

import ai.core.rag.DistanceMetricType;

/**
 * @author stephen
 *
 * @param dimension: vector dimension
 * @param maxItemCount: max item count, suggest to = dimension
 * @param ef: search parameter, the higher ef, the more accurate the search, but slower, suggest to set 10-200
 * @param efConstruction: construction parameter, the higher efConstruction, the more accurate the search, but slower, suggest to set 100-200
 * @param m: construction parameter, the higher m, the more accurate the search, but slower, suggest to set 12-24
 * @param metricType: distance metric type
 */
public record HnswConfig(String path, int dimension, int maxItemCount, int ef, int efConstruction, int m, DistanceMetricType metricType) {
    public static HnswConfig of(String path) {
        return of(path, 1536);
    }
    public static HnswConfig of(String path, int dimension) {
        return new HnswConfig(path, dimension, dimension, 40, 200, 16, DistanceMetricType.EUCLIDEAN);
    }
}
