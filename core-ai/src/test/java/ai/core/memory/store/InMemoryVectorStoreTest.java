package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InMemoryVectorStore.
 *
 * @author xander
 */
class InMemoryVectorStoreTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryVectorStoreTest.class);

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
    }

    @Test
    void testSaveAndGet() {
        var entry = createEntry("test-1", "Test content");
        store.save(entry);

        var result = store.get("test-1");

        assertTrue(result.isPresent());
        assertEquals("Test content", result.get().getContent());

        LOGGER.info("save and get test passed");
    }

    @Test
    void testSaveNull() {
        store.save(null);

        assertEquals(0, store.size());

        LOGGER.info("save null test passed");
    }

    @Test
    void testSaveBatch() {
        var entries = List.of(
            createEntry("test-1", "Content 1"),
            createEntry("test-2", "Content 2"),
            createEntry("test-3", "Content 3")
        );

        store.saveBatch(entries);

        assertEquals(3, store.size());
        assertTrue(store.get("test-1").isPresent());
        assertTrue(store.get("test-2").isPresent());
        assertTrue(store.get("test-3").isPresent());

        LOGGER.info("save batch test passed");
    }

    @Test
    void testUpdate() {
        var entry = createEntry("test-1", "Original content");
        store.save(entry);

        var updatedEntry = createEntry("test-1", "Updated content");
        store.update("test-1", updatedEntry);

        var result = store.get("test-1");

        assertTrue(result.isPresent());
        assertEquals("Updated content", result.get().getContent());

        LOGGER.info("update test passed");
    }

    @Test
    void testDelete() {
        var entry = createEntry("test-1", "Content");
        store.save(entry);

        store.delete("test-1");

        assertFalse(store.get("test-1").isPresent());

        LOGGER.info("delete test passed");
    }

    @Test
    void testDeleteBatch() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));
        store.save(createEntry("test-3", "Content 3"));

        store.deleteBatch(List.of("test-1", "test-3"));

        assertEquals(1, store.size());
        assertFalse(store.get("test-1").isPresent());
        assertTrue(store.get("test-2").isPresent());
        assertFalse(store.get("test-3").isPresent());

        LOGGER.info("delete batch test passed");
    }

    @Test
    void testSimilaritySearch() {
        // Create entries with embeddings
        var entry1 = createEntryWithEmbedding("test-1", "Apple is a fruit", List.of(1.0, 0.0, 0.0));
        var entry2 = createEntryWithEmbedding("test-2", "Orange is a fruit", List.of(0.9, 0.1, 0.0));
        var entry3 = createEntryWithEmbedding("test-3", "Car is a vehicle", List.of(0.0, 0.0, 1.0));

        store.save(entry1);
        store.save(entry2);
        store.save(entry3);

        // Query similar to fruits
        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var results = store.similaritySearch(queryEmbedding, 2, 0.5, null);

        assertEquals(2, results.size());
        assertEquals("test-1", results.get(0).entry().getId());
        assertEquals("test-2", results.get(1).entry().getId());
        assertTrue(results.get(0).score() >= results.get(1).score());

        LOGGER.info("similarity search test passed, scores: {}, {}",
            results.get(0).score(), results.get(1).score());
    }

    @Test
    void testSimilaritySearchWithThreshold() {
        var entry1 = createEntryWithEmbedding("test-1", "Similar", List.of(1.0, 0.0, 0.0));
        var entry2 = createEntryWithEmbedding("test-2", "Different", List.of(0.0, 1.0, 0.0));

        store.save(entry1);
        store.save(entry2);

        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var results = store.similaritySearch(queryEmbedding, 10, 0.9, null);

        // Only entry1 should match with high threshold
        assertEquals(1, results.size());
        assertEquals("test-1", results.get(0).entry().getId());

        LOGGER.info("similarity search with threshold test passed");
    }

    @Test
    void testSimilaritySearchWithFilter() {
        var entry1 = createEntryWithEmbedding("test-1", "Content 1", List.of(1.0, 0.0, 0.0));
        entry1.setUserId("user-1");
        var entry2 = createEntryWithEmbedding("test-2", "Content 2", List.of(1.0, 0.0, 0.0));
        entry2.setUserId("user-2");

        store.save(entry1);
        store.save(entry2);

        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var filter = MemoryFilter.forUser("user-1");
        var results = store.similaritySearch(queryEmbedding, 10, 0.5, filter);

        assertEquals(1, results.size());
        assertEquals("user-1", results.get(0).entry().getUserId());

        LOGGER.info("similarity search with filter test passed");
    }

    @Test
    void testSimilaritySearchNullEmbedding() {
        var entry = createEntry("test-1", "Content");
        store.save(entry);

        var results = store.similaritySearch(null, 10, 0.5, null);

        assertTrue(results.isEmpty());

        LOGGER.info("similarity search null embedding test passed");
    }

    @Test
    void testSimilaritySearchNoEmbeddings() {
        var entry = createEntry("test-1", "Content");
        store.save(entry);

        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var results = store.similaritySearch(queryEmbedding, 10, 0.5, null);

        assertTrue(results.isEmpty());

        LOGGER.info("similarity search no embeddings test passed");
    }

    @Test
    void testFindAll() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));
        store.save(createEntry("test-3", "Content 3"));

        var results = store.findAll(null, 10);

        assertEquals(3, results.size());

        LOGGER.info("findAll test passed");
    }

    @Test
    void testFindAllWithLimit() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));
        store.save(createEntry("test-3", "Content 3"));

        var results = store.findAll(null, 2);

        assertEquals(2, results.size());

        LOGGER.info("findAll with limit test passed");
    }

    @Test
    void testFindAllWithFilter() {
        var entry1 = createEntry("test-1", "Content 1");
        entry1.setUserId("user-1");
        var entry2 = createEntry("test-2", "Content 2");
        entry2.setUserId("user-2");

        store.save(entry1);
        store.save(entry2);

        var filter = MemoryFilter.forUser("user-1");
        var results = store.findAll(filter, 10);

        assertEquals(1, results.size());
        assertEquals("user-1", results.get(0).getUserId());

        LOGGER.info("findAll with filter test passed");
    }

    @Test
    void testCount() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));
        store.save(createEntry("test-3", "Content 3"));

        assertEquals(3, store.count(null));

        LOGGER.info("count test passed");
    }

    @Test
    void testCountWithFilter() {
        var entry1 = createEntry("test-1", "Content 1");
        entry1.setUserId("user-1");
        var entry2 = createEntry("test-2", "Content 2");
        entry2.setUserId("user-1");
        var entry3 = createEntry("test-3", "Content 3");
        entry3.setUserId("user-2");

        store.save(entry1);
        store.save(entry2);
        store.save(entry3);

        var filter = MemoryFilter.forUser("user-1");
        assertEquals(2, store.count(filter));

        LOGGER.info("count with filter test passed");
    }

    @Test
    void testClear() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.getAll().isEmpty());

        LOGGER.info("clear test passed");
    }

    @Test
    void testGetAll() {
        store.save(createEntry("test-1", "Content 1"));
        store.save(createEntry("test-2", "Content 2"));

        var all = store.getAll();

        assertEquals(2, all.size());

        LOGGER.info("getAll test passed");
    }

    @Test
    void testCosineSimilarityIdentical() {
        var entry = createEntryWithEmbedding("test-1", "Same", List.of(1.0, 2.0, 3.0));
        store.save(entry);

        var queryEmbedding = new Embedding(List.of(1.0, 2.0, 3.0));
        var results = store.similaritySearch(queryEmbedding, 1, 0.0, null);

        assertEquals(1, results.size());
        assertEquals(1.0, results.get(0).score(), 0.0001);

        LOGGER.info("cosine similarity identical test passed");
    }

    @Test
    void testCosineSimilarityOrthogonal() {
        var entry = createEntryWithEmbedding("test-1", "Different", List.of(1.0, 0.0, 0.0));
        store.save(entry);

        var queryEmbedding = new Embedding(List.of(0.0, 1.0, 0.0));
        var results = store.similaritySearch(queryEmbedding, 1, 0.0, null);

        assertEquals(1, results.size());
        assertEquals(0.0, results.get(0).score(), 0.0001);

        LOGGER.info("cosine similarity orthogonal test passed");
    }

    private MemoryEntry createEntry(String id, String content) {
        return MemoryEntry.builder()
            .id(id)
            .content(content)
            .type(MemoryType.SEMANTIC)
            .build();
    }

    private MemoryEntry createEntryWithEmbedding(String id, String content, List<Double> vectors) {
        var entry = MemoryEntry.builder()
            .id(id)
            .content(content)
            .type(MemoryType.SEMANTIC)
            .embedding(new Embedding(vectors))
            .build();
        return entry;
    }
}
