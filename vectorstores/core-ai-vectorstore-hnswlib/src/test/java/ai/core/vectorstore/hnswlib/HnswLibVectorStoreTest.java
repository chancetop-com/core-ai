package ai.core.vectorstore.hnswlib;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.vectorstore.request.ScalarQueryRequest;
import ai.core.vectorstore.spec.CollectionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HnswLibVectorStoreTest {
    @TempDir
    Path tempDir;

    private HnswLibVectorStore store() {
        return new HnswLibVectorStore(HnswConfig.of(tempDir.resolve("test.hnsw").toString(), 4));
    }

    @Test
    void ensureCollectionCreatesEmptyIndex() {
        var store = store();
        assertFalse(store.hasCollection("any"));
        store.ensureCollection(CollectionSpec.builder("any").dimension(4).build());
        assertTrue(store.hasCollection("any"));
        assertEquals(List.of(), store.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(new Embedding(List.of(1.0, 0.0, 0.0, 0.0))).topK(3).threshold(100d).build()));
    }

    @Test
    void addGetAndDeleteByIds() {
        var store = store();
        store.ensureCollection(CollectionSpec.builder("col").dimension(4).build());
        store.add(null, List.of(
                new Document("d1", new Embedding(List.of(1.0, 0.0, 0.0, 0.0)), "hello", Map.of("tag", "a")),
                new Document("d2", new Embedding(List.of(0.0, 1.0, 0.0, 0.0)), "world", Map.of())));
        assertEquals("hello", store.getById(null, "d1").orElseThrow().content);
        assertEquals(2, store.getByIds(null, List.of("d1", "d2", "missing")).size());
        store.deleteByIds(null, List.of("d1"));
        assertTrue(store.getById(null, "d1").isEmpty());
    }

    @Test
    void similaritySearchFillsScore() {
        var store = store();
        store.ensureCollection(CollectionSpec.builder("col").dimension(4).build());
        store.add(null, List.of(
                new Document("d1", new Embedding(List.of(1.0, 0.0, 0.0, 0.0)), "hello", Map.of()),
                new Document("d2", new Embedding(List.of(0.0, 1.0, 0.0, 0.0)), "world", Map.of())));
        var results = store.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(new Embedding(List.of(1.0, 0.1, 0.0, 0.0))).topK(2).threshold(100d).build());
        assertEquals(2, results.size());
        assertEquals("d1", results.getFirst().id);
        assertNotNull(results.getFirst().score);
        assertEquals("hello", results.getFirst().content);
    }

    @Test
    void similaritySearchAppliesThreshold() {
        var store = store();
        store.ensureCollection(CollectionSpec.builder("col").dimension(4).build());
        store.add(null, List.of(new Document("d1", new Embedding(List.of(1.0, 0.0, 0.0, 0.0)), "hello", Map.of())));
        var results = store.similaritySearch(SimilaritySearchRequest.builder()
                .embedding(new Embedding(List.of(0.0, 1.0, 0.0, 0.0))).topK(1).threshold(0.1d).build());
        assertTrue(results.isEmpty());
    }

    @Test
    void dropCollectionRemovesFile() {
        var store = store();
        store.ensureCollection(CollectionSpec.builder("col").dimension(4).build());
        store.dropCollection("col");
        assertFalse(store.hasCollection("col"));
    }

    @Test
    void unsupportedOperations() {
        var store = store();
        assertThrows(UnsupportedOperationException.class, () -> store.query(ScalarQueryRequest.builder().filter("x == 1").build()));
        assertThrows(UnsupportedOperationException.class, () -> store.deleteByFilter(null, "x == 1"));
    }
}
