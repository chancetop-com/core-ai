package ai.core.vectorstore.milvus;

import ai.core.document.Document;
import ai.core.document.Embedding;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MilvusVectorStoreTest {
    private static MilvusVectorStore store() {
        return new MilvusVectorStore(MilvusConfig.builder().uri("http://localhost:19530").collection("test_col").build());
    }

    @Test
    void idValueConversion() {
        var store = store();
        assertEquals(123L, store.toIdValue("123", DataType.Int64));
        assertEquals("abc", store.toIdValue("abc", DataType.VarChar));
    }

    @Test
    void thresholdFilteringForSimilarityMetrics() {
        var store = store();
        assertTrue(store.passThreshold(0.8d, 0.7d, IndexParam.MetricType.COSINE));
        assertFalse(store.passThreshold(0.6d, 0.7d, IndexParam.MetricType.COSINE));
        assertTrue(store.passThreshold(0.8d, 0.7d, IndexParam.MetricType.IP));
        assertFalse(store.passThreshold(0.6d, 0.7d, IndexParam.MetricType.IP));
    }

    @Test
    void thresholdFilteringForDistanceMetric() {
        var store = store();
        assertTrue(store.passThreshold(0.6d, 0.0d, IndexParam.MetricType.L2));
        assertTrue(store.passThreshold(0.5d, 0.7d, IndexParam.MetricType.L2));
        assertFalse(store.passThreshold(0.8d, 0.7d, IndexParam.MetricType.L2));
    }

    @Test
    void thresholdFilteringSkipsNullScore() {
        var store = store();
        assertTrue(store.passThreshold(null, 0.7d, IndexParam.MetricType.COSINE));
    }

    @Test
    void searchResultMapping() {
        var store = store();
        var info = new MilvusVectorStore.CollectionInfo("vector", "id", DataType.Int64, true, IndexParam.MetricType.COSINE, Set.of("id", "vector", "query"));
        var entity = new HashMap<String, Object>();
        entity.put("id", 42L);
        entity.put("vector", List.of(1.0f, 2.0f));
        entity.put("query", "hello");
        entity.put("media_library_id", "lib-1");
        var doc = store.toDocument(42L, entity, info, List.of("vector", "query", "media_library_id"), 0.9f);
        assertEquals("42", doc.id);
        assertEquals("hello", doc.content);
        assertEquals(0.9d, doc.score, 1e-6);
        assertEquals(List.of(1.0d, 2.0d), doc.embedding.vectors());
        assertEquals("lib-1", doc.extraField.get("media_library_id"));
        assertNull(doc.extraField.get("query"));
        assertNull(doc.extraField.get("vector"));
    }

    @Test
    void queryResultMappingWithStringPrimaryKey() {
        var store = store();
        var info = new MilvusVectorStore.CollectionInfo("vector", "doc_id", DataType.VarChar, false, IndexParam.MetricType.L2, Set.of("doc_id", "vector"));
        var entity = new HashMap<String, Object>();
        entity.put("doc_id", "doc-7");
        entity.put("vector", List.of(0.5f));
        var doc = store.toDocument("doc-7", entity, info, List.of("vector"), null);
        assertEquals("doc-7", doc.id);
        assertNull(doc.score);
        assertNull(doc.extraField);
        assertEquals(List.of(0.5d), doc.embedding.vectors());
    }

    @Test
    void queryResultMappingWithoutVector() {
        var store = store();
        var info = new MilvusVectorStore.CollectionInfo("vector", "id", DataType.Int64, true, IndexParam.MetricType.COSINE, Set.of("id", "vector", "query"));
        var entity = new HashMap<String, Object>();
        entity.put("id", 1L);
        entity.put("merchant_id", "m-1");
        var doc = store.toDocument(1L, entity, info, List.of("merchant_id"), null);
        assertEquals("1", doc.id);
        assertNull(doc.embedding);
        assertEquals("m-1", doc.extraField.get("merchant_id"));
    }

    @Test
    void rowMappingSkipsIdForAutoIdCollection() {
        var store = store();
        var info = new MilvusVectorStore.CollectionInfo("vector", "id", DataType.Int64, true, IndexParam.MetricType.COSINE, Set.of("id", "vector", "query"));
        var doc = new Document("42", new Embedding(List.of(1.0d, 2.0d)), "hello", Map.of("merchant_id", "m-1"));
        var row = store.row(doc, info);
        assertFalse(row.has("id"));
        assertEquals(1.0f, row.getAsJsonArray("vector").get(0).getAsFloat());
        assertEquals("hello", row.get("query").getAsString());
        assertEquals("m-1", row.get("merchant_id").getAsString());
    }

    @Test
    void rowMappingWritesIdForNonAutoIdCollection() {
        var store = store();
        var info = new MilvusVectorStore.CollectionInfo("vector", "doc_id", DataType.VarChar, false, IndexParam.MetricType.COSINE, Set.of("doc_id", "vector", "query"));
        var doc = new Document("doc-1", null, null, null);
        var row = store.row(doc, info);
        assertEquals("doc-1", row.get("doc_id").getAsString());
        assertFalse(row.has("vector"));
        assertFalse(row.has("query"));
    }
}
