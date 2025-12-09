package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.model.SemanticCategory;
import ai.core.memory.model.SemanticMemoryEntry;
import ai.core.memory.decay.ExponentialDecayPolicy;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for HybridMemoryStore.
 *
 * @author xander
 */
class HybridMemoryStoreTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(HybridMemoryStoreTest.class);

    private HybridMemoryStore hybridStore;
    private InMemoryVectorStore vectorStore;
    private InMemoryKVStore kvStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        kvStore = new InMemoryKVStore();
        hybridStore = new HybridMemoryStore(vectorStore, kvStore, null);
    }

    @Test
    void testAddMemoryEntry() {
        var entry = createSemanticEntry("user-1", "coffee", "User likes coffee");

        hybridStore.add(entry);

        // Verify in vector store
        assertEquals(1, vectorStore.size());
        assertTrue(vectorStore.get(entry.getId()).isPresent());

        // Verify in KV store (semantic entries with subject)
        assertTrue(kvStore.get("user-1:coffee").isPresent());

        LOGGER.info("add memory entry test passed");
    }

    @Test
    void testAddStringContent() {
        hybridStore.add("Simple text content");

        assertEquals(1, hybridStore.size());

        LOGGER.info("add string content test passed");
    }

    @Test
    void testAddBatch() {
        var entries = List.of(
            createSemanticEntry("user-1", "coffee", "Likes coffee"),
            createSemanticEntry("user-1", "tea", "Likes tea"),
            MemoryEntry.builder()
                .userId("user-1")
                .content("General memory")
                .type(MemoryType.EPISODIC)
                .build()
        );

        hybridStore.addBatch(entries);

        assertEquals(3, hybridStore.size());
        assertTrue(kvStore.get("user-1:coffee").isPresent());
        assertTrue(kvStore.get("user-1:tea").isPresent());

        LOGGER.info("add batch test passed");
    }

    @Test
    void testUpdate() {
        var entry = createSemanticEntry("user-1", "drink", "Likes coffee");
        hybridStore.add(entry);

        var updatedEntry = createSemanticEntry("user-1", "drink", "Prefers tea now");
        updatedEntry.setId(entry.getId());
        hybridStore.update(entry.getId(), updatedEntry);

        var result = hybridStore.getById(entry.getId());
        assertTrue(result.isPresent());
        assertEquals("Prefers tea now", result.get().getContent());

        LOGGER.info("update test passed");
    }

    @Test
    void testDelete() {
        var entry = createSemanticEntry("user-1", "coffee", "Likes coffee");
        hybridStore.add(entry);

        hybridStore.delete(entry.getId());

        assertFalse(hybridStore.getById(entry.getId()).isPresent());
        assertFalse(kvStore.get("user-1:coffee").isPresent());

        LOGGER.info("delete test passed");
    }

    @Test
    void testDeleteBatch() {
        var entry1 = createSemanticEntry("user-1", "a", "Content A");
        var entry2 = createSemanticEntry("user-1", "b", "Content B");
        var entry3 = createSemanticEntry("user-1", "c", "Content C");

        hybridStore.add(entry1);
        hybridStore.add(entry2);
        hybridStore.add(entry3);

        hybridStore.deleteBatch(List.of(entry1.getId(), entry3.getId()));

        assertEquals(1, hybridStore.size());
        assertTrue(hybridStore.getById(entry2.getId()).isPresent());

        LOGGER.info("delete batch test passed");
    }

    @Test
    void testRetrieveWithFilterViaNullQueryFallback() {
        // When llmProvider is null, vector search doesn't work
        // Use null/empty query to trigger findAll fallback
        var entry1 = createSemanticEntry("user-1", "coffee", "User 1 likes coffee");
        var entry2 = createSemanticEntry("user-2", "tea", "User 2 likes tea");

        hybridStore.add(entry1);
        hybridStore.add(entry2);

        var filter = MemoryFilter.forUser("user-1");
        // Use empty query to trigger findAll behavior
        var results = hybridStore.retrieve("", 10, filter);

        assertEquals(1, results.size());
        assertEquals("user-1", results.get(0).getUserId());

        LOGGER.info("retrieve with filter via null query fallback test passed");
    }

    @Test
    void testRetrieveEmptyQuery() {
        hybridStore.add("Memory about apples");
        hybridStore.add("Memory about oranges");

        // Without llmProvider, use empty query to trigger findAll
        var results = hybridStore.retrieve("", 10);

        // Empty query triggers findAll, returns all entries
        assertEquals(2, results.size());

        LOGGER.info("retrieve empty query test passed");
    }

    @Test
    void testRetrieveWithQueryNoEmbeddings() {
        // When llmProvider is null, query with text returns empty (no vector search works)
        hybridStore.add("Memory about apples");
        hybridStore.add("Memory about oranges");

        var results = hybridStore.retrieve("fruits", 10);

        // Without llmProvider, vector search returns empty (no KV match either)
        assertEquals(0, results.size());

        LOGGER.info("retrieve with query no embeddings test passed");
    }

    @Test
    void testGetByUserId() {
        var entry1 = MemoryEntry.builder()
            .userId("user-1")
            .content("User 1 content")
            .type(MemoryType.SEMANTIC)
            .build();
        var entry2 = MemoryEntry.builder()
            .userId("user-2")
            .content("User 2 content")
            .type(MemoryType.SEMANTIC)
            .build();

        hybridStore.add(entry1);
        hybridStore.add(entry2);

        var results = hybridStore.getByUserId("user-1", null, 10);

        assertEquals(1, results.size());
        assertEquals("user-1", results.get(0).getUserId());

        LOGGER.info("getByUserId test passed");
    }

    @Test
    void testGetByUserIdWithType() {
        var semantic = MemoryEntry.builder()
            .userId("user-1")
            .content("Semantic content")
            .type(MemoryType.SEMANTIC)
            .build();
        var episodic = MemoryEntry.builder()
            .userId("user-1")
            .content("Episodic content")
            .type(MemoryType.EPISODIC)
            .build();

        hybridStore.add(semantic);
        hybridStore.add(episodic);

        var results = hybridStore.getByUserId("user-1", MemoryType.SEMANTIC, 10);

        assertEquals(1, results.size());
        assertEquals(MemoryType.SEMANTIC, results.get(0).getType());

        LOGGER.info("getByUserId with type test passed");
    }

    @Test
    void testFindSimilar() {
        var entry1 = MemoryEntry.builder()
            .content("Similar 1")
            .type(MemoryType.SEMANTIC)
            .embedding(new Embedding(List.of(1.0, 0.0, 0.0)))
            .build();
        var entry2 = MemoryEntry.builder()
            .content("Different")
            .type(MemoryType.SEMANTIC)
            .embedding(new Embedding(List.of(0.0, 1.0, 0.0)))
            .build();

        hybridStore.add(entry1);
        hybridStore.add(entry2);

        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var results = hybridStore.findSimilar(queryEmbedding, 1, 0.9);

        assertEquals(1, results.size());
        assertEquals("Similar 1", results.get(0).getContent());

        LOGGER.info("findSimilar test passed");
    }

    @Test
    void testApplyDecay() {
        var oldEntry = MemoryEntry.builder()
            .content("Old memory")
            .type(MemoryType.SEMANTIC)
            .strength(1.0)
            .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
            .lastAccessedAt(Instant.now().minus(30, ChronoUnit.DAYS))
            .build();

        hybridStore.add(oldEntry);

        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));
        hybridStore.applyDecay(policy);

        var result = hybridStore.getById(oldEntry.getId());
        assertTrue(result.isPresent());
        assertTrue(result.get().getStrength() < 1.0);

        LOGGER.info("applyDecay test passed, new strength={}", result.get().getStrength());
    }

    @Test
    void testGetDecayedMemories() {
        var strongEntry = MemoryEntry.builder()
            .content("Strong")
            .type(MemoryType.SEMANTIC)
            .strength(0.8)
            .build();
        var weakEntry = MemoryEntry.builder()
            .content("Weak")
            .type(MemoryType.SEMANTIC)
            .strength(0.05)
            .build();

        hybridStore.add(strongEntry);
        hybridStore.add(weakEntry);

        var decayed = hybridStore.getDecayedMemories(0.1);

        assertEquals(1, decayed.size());
        assertEquals("Weak", decayed.get(0).getContent());

        LOGGER.info("getDecayedMemories test passed");
    }

    @Test
    void testRemoveDecayedMemories() {
        var strongEntry = MemoryEntry.builder()
            .content("Strong")
            .type(MemoryType.SEMANTIC)
            .strength(0.8)
            .build();
        var weakEntry = MemoryEntry.builder()
            .content("Weak")
            .type(MemoryType.SEMANTIC)
            .strength(0.05)
            .build();

        hybridStore.add(strongEntry);
        hybridStore.add(weakEntry);

        int removed = hybridStore.removeDecayedMemories(0.1);

        assertEquals(1, removed);
        assertEquals(1, hybridStore.size());
        assertTrue(hybridStore.getById(strongEntry.getId()).isPresent());

        LOGGER.info("removeDecayedMemories test passed");
    }

    @Test
    void testBuildContext() {
        hybridStore.add("Memory 1");
        hybridStore.add("Memory 2");

        String context = hybridStore.buildContext();

        assertTrue(context.contains("[Long-term Memory]"));
        assertTrue(context.contains("Memory 1") || context.contains("Memory 2"));

        LOGGER.info("buildContext test passed: {}", context);
    }

    @Test
    void testBuildContextEmpty() {
        String context = hybridStore.buildContext();

        assertEquals("", context);

        LOGGER.info("buildContext empty test passed");
    }

    @Test
    void testClear() {
        hybridStore.add("Content 1");
        hybridStore.add("Content 2");

        hybridStore.clear();

        assertEquals(0, hybridStore.size());

        LOGGER.info("clear test passed");
    }

    @Test
    void testSize() {
        assertEquals(0, hybridStore.size());

        hybridStore.add("Content 1");
        assertEquals(1, hybridStore.size());

        hybridStore.add("Content 2");
        assertEquals(2, hybridStore.size());

        LOGGER.info("size test passed");
    }

    private SemanticMemoryEntry createSemanticEntry(String userId, String subject, String content) {
        return SemanticMemoryEntry.builder()
            .userId(userId)
            .subject(subject)
            .content(content)
            .category(SemanticCategory.PREFERENCE)
            .build();
    }
}
