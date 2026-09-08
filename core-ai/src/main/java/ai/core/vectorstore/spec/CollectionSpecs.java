package ai.core.vectorstore.spec;

import ai.core.rag.DistanceMetricType;

import java.util.Map;

/**
 * Predefined {@link CollectionSpec} factories for the built-in RAG collections.
 *
 * @author stephen
 */
public final class CollectionSpecs {
    public static CollectionSpec wiki(String name, int dimension, int trunkSize) {
        return CollectionSpec.builder(name)
                .dimension(dimension)
                .indexType(IndexType.IVF_FLAT)
                .metricType(DistanceMetricType.COSINE)
                .indexParams(Map.of("nlist", 256))
                .scalarField(FieldSpec.varchar("query", trunkSize))
                .build();
    }

    public static CollectionSpec imageCaption(String name, int dimension, int trunkSize) {
        return CollectionSpec.builder(name)
                .dimension(dimension)
                .indexType(IndexType.IVF_FLAT)
                .metricType(DistanceMetricType.COSINE)
                .indexParams(Map.of("nlist", 256))
                .scalarField(FieldSpec.varchar("url", 500))
                .scalarField(FieldSpec.varchar("query", trunkSize))
                .build();
    }

    private CollectionSpecs() {
    }
}
