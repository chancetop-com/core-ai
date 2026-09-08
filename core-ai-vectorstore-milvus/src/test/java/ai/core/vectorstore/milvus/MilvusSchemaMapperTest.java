package ai.core.vectorstore.milvus;

import ai.core.rag.DistanceMetricType;
import ai.core.vectorstore.spec.CollectionSpec;
import ai.core.vectorstore.spec.FieldSpec;
import ai.core.vectorstore.spec.IndexType;
import ai.core.vectorstore.spec.ScalarType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MilvusSchemaMapperTest {
    @Test
    void dataTypeMapping() {
        assertEquals(DataType.Int64, MilvusSchemaMapper.dataType(ScalarType.INT64));
        assertEquals(DataType.VarChar, MilvusSchemaMapper.dataType(ScalarType.VARCHAR));
        assertEquals(DataType.Bool, MilvusSchemaMapper.dataType(ScalarType.BOOL));
        assertEquals(DataType.Float, MilvusSchemaMapper.dataType(ScalarType.FLOAT));
        assertEquals(DataType.Double, MilvusSchemaMapper.dataType(ScalarType.DOUBLE));
        assertEquals(DataType.JSON, MilvusSchemaMapper.dataType(ScalarType.JSON));
    }

    @Test
    void indexTypeMapping() {
        assertEquals(IndexParam.IndexType.FLAT, MilvusSchemaMapper.indexType(IndexType.FLAT));
        assertEquals(IndexParam.IndexType.IVF_FLAT, MilvusSchemaMapper.indexType(IndexType.IVF_FLAT));
        assertEquals(IndexParam.IndexType.HNSW, MilvusSchemaMapper.indexType(IndexType.HNSW));
        assertEquals(IndexParam.IndexType.AUTOINDEX, MilvusSchemaMapper.indexType(IndexType.AUTOINDEX));
    }

    @Test
    void metricTypeMapping() {
        assertEquals(IndexParam.MetricType.L2, MilvusSchemaMapper.metricType(DistanceMetricType.EUCLIDEAN));
        assertEquals(IndexParam.MetricType.IP, MilvusSchemaMapper.metricType(DistanceMetricType.PRODUCT));
        assertEquals(IndexParam.MetricType.COSINE, MilvusSchemaMapper.metricType(DistanceMetricType.COSINE));
    }

    @Test
    void unsupportedMetricType() {
        assertThrows(IllegalArgumentException.class, () -> MilvusSchemaMapper.metricType(DistanceMetricType.MANHATTAN));
        assertThrows(IllegalArgumentException.class, () -> MilvusSchemaMapper.metricType(DistanceMetricType.CANBERRA));
    }

    @Test
    void schemaMapping() {
        var spec = CollectionSpec.builder("menu_col")
                .dimension(768)
                .indexType(IndexType.HNSW)
                .metricType(DistanceMetricType.COSINE)
                .scalarField(FieldSpec.varchar("media_library_id", 64))
                .scalarField(FieldSpec.varchar("merchant_id", 64))
                .scalarField(FieldSpec.varchar("img_path", 512))
                .build();
        var schema = MilvusSchemaMapper.schema(spec);
        assertEquals(5, schema.getFieldSchemaList().size());
        var pk = schema.getField("id");
        assertEquals(DataType.Int64, pk.getDataType());
        assertTrue(pk.getIsPrimaryKey());
        assertTrue(pk.getAutoID());
        var vector = schema.getField("vector");
        assertEquals(DataType.FloatVector, vector.getDataType());
        assertEquals(768, vector.getDimension());
        assertEquals(64, schema.getField("media_library_id").getMaxLength());
        assertEquals(512, schema.getField("img_path").getMaxLength());
        assertFalse(schema.isEnableDynamicField());
    }

    @Test
    void varcharDefaultsTo512Length() {
        var spec = CollectionSpec.builder("c")
                .dimension(4)
                .scalarField(new FieldSpec("name", ScalarType.VARCHAR, null, false, false))
                .build();
        var schema = MilvusSchemaMapper.schema(spec);
        assertEquals(512, schema.getField("name").getMaxLength());
    }

    @Test
    void indexMapping() {
        var spec = CollectionSpec.builder("menu_col")
                .dimension(768)
                .indexType(IndexType.IVF_FLAT)
                .metricType(DistanceMetricType.COSINE)
                .indexParams(java.util.Map.of("nlist", 256))
                .build();
        var index = MilvusSchemaMapper.index(spec);
        assertEquals("vector", index.getFieldName());
        assertEquals("vectorIndex", index.getIndexName());
        assertEquals(IndexParam.IndexType.IVF_FLAT, index.getIndexType());
        assertEquals(IndexParam.MetricType.COSINE, index.getMetricType());
    }
}
