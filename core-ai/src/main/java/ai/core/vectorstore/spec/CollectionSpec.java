package ai.core.vectorstore.spec;

import ai.core.rag.DistanceMetricType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema description of a vector collection, decoupled from any concrete vector store SDK.
 *
 * @author stephen
 */
public record CollectionSpec(
        String name,
        FieldSpec primaryKey,               // defaults to autoIdInt64("id")
        String vectorField,                 // defaults to "vector"
        int dimension,
        IndexType indexType,                // defaults to HNSW
        DistanceMetricType metricType,      // defaults to COSINE
        Map<String, Object> indexParams,    // e.g. {"M":16,"efConstruction":200} or {"nlist":256}
        List<FieldSpec> scalarFields,
        boolean enableDynamicField
) {
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private FieldSpec primaryKey = FieldSpec.autoIdInt64("id");
        private String vectorField = "vector";
        private Integer dimension;
        private IndexType indexType = IndexType.HNSW;
        private DistanceMetricType metricType = DistanceMetricType.COSINE;
        private Map<String, Object> indexParams;
        private final List<FieldSpec> scalarFields = new ArrayList<>();
        private Boolean enableDynamicField = Boolean.FALSE;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("collection name must not be blank");
            this.name = name;
        }

        public Builder primaryKey(FieldSpec primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public Builder vectorField(String vectorField) {
            this.vectorField = vectorField;
            return this;
        }

        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder indexType(IndexType indexType) {
            this.indexType = indexType;
            return this;
        }

        public Builder metricType(DistanceMetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        public Builder indexParams(Map<String, Object> indexParams) {
            this.indexParams = indexParams;
            return this;
        }

        public Builder scalarField(FieldSpec field) {
            this.scalarFields.add(field);
            return this;
        }

        public Builder scalarFields(List<FieldSpec> fields) {
            this.scalarFields.addAll(fields);
            return this;
        }

        public Builder enableDynamicField(Boolean enableDynamicField) {
            this.enableDynamicField = enableDynamicField;
            return this;
        }

        public CollectionSpec build() {
            if (dimension == null || dimension <= 0) throw new IllegalArgumentException("dimension must be positive");
            if (indexParams == null) indexParams = new HashMap<>();
            return new CollectionSpec(name, primaryKey, vectorField, dimension, indexType, metricType,
                    indexParams, List.copyOf(scalarFields), Boolean.TRUE.equals(enableDynamicField));
        }
    }
}
