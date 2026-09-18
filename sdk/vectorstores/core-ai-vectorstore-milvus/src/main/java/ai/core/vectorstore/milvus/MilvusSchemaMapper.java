package ai.core.vectorstore.milvus;

import ai.core.rag.DistanceMetricType;
import ai.core.vectorstore.spec.CollectionSpec;
import ai.core.vectorstore.spec.FieldSpec;
import ai.core.vectorstore.spec.IndexType;
import ai.core.vectorstore.spec.ScalarType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

/**
 * Pure mapping between core-ai collection specs and Milvus SDK types, kept separate from the client
 * so it can be unit tested without a running Milvus.
 *
 * @author stephen
 */
public final class MilvusSchemaMapper {
    public static DataType dataType(ScalarType type) {
        return switch (type) {
            case INT64 -> DataType.Int64;
            case VARCHAR -> DataType.VarChar;
            case BOOL -> DataType.Bool;
            case FLOAT -> DataType.Float;
            case DOUBLE -> DataType.Double;
            case JSON -> DataType.JSON;
        };
    }

    public static IndexParam.IndexType indexType(IndexType type) {
        return switch (type) {
            case FLAT -> IndexParam.IndexType.FLAT;
            case IVF_FLAT -> IndexParam.IndexType.IVF_FLAT;
            case HNSW -> IndexParam.IndexType.HNSW;
            case AUTOINDEX -> IndexParam.IndexType.AUTOINDEX;
        };
    }

    public static IndexParam.MetricType metricType(DistanceMetricType type) {
        return switch (type) {
            case EUCLIDEAN -> IndexParam.MetricType.L2;
            case PRODUCT -> IndexParam.MetricType.IP;
            case COSINE -> IndexParam.MetricType.COSINE;
            default -> throw new IllegalArgumentException("metric type not supported by milvus: " + type);
        };
    }

    public static CreateCollectionReq.CollectionSchema schema(CollectionSpec spec) {
        var schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(spec.enableDynamicField())
                .build();
        schema.addField(primaryKey(spec));
        schema.addField(AddFieldReq.builder()
                .fieldName(spec.vectorField())
                .dataType(DataType.FloatVector)
                .dimension(spec.dimension())
                .build());
        for (var field : spec.scalarFields()) {
            schema.addField(scalarField(field));
        }
        return schema;
    }

    public static IndexParam index(CollectionSpec spec) {
        return IndexParam.builder()
                .fieldName(spec.vectorField())
                .indexName(spec.vectorField() + "Index")
                .indexType(indexType(spec.indexType()))
                .metricType(metricType(spec.metricType()))
                .extraParams(spec.indexParams())
                .build();
    }

    private static AddFieldReq primaryKey(CollectionSpec spec) {
        var field = spec.primaryKey();
        var builder = AddFieldReq.builder()
                .fieldName(field.name())
                .dataType(dataType(field.type()))
                .isPrimaryKey(Boolean.TRUE)
                .autoID(field.autoId());
        if (field.maxLength() != null) builder.maxLength(field.maxLength());
        return builder.build();
    }

    private static AddFieldReq scalarField(FieldSpec field) {
        var builder = AddFieldReq.builder()
                .fieldName(field.name())
                .dataType(dataType(field.type()));
        if (field.type() == ScalarType.VARCHAR) builder.maxLength(field.maxLength() != null ? field.maxLength() : 512);
        return builder.build();
    }

    private MilvusSchemaMapper() {
    }
}
