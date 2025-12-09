package ai.core.memory;

import ai.core.memory.decay.ExponentialDecayPolicy;
import ai.core.memory.model.MemoryContext;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.model.RetrievalOptions;
import ai.core.memory.store.HybridMemoryStore;
import ai.core.memory.store.InMemoryKVStore;
import ai.core.memory.store.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryManager.
 *
 * @author xander
 */
class MemoryManagerTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryManagerTest.class);

    private MemoryManager memoryManager;
    private HybridMemoryStore memoryStore;
    private InMemoryVectorStore vectorStore;
    private InMemoryKVStore kvStore;
    private MemoryConfig config;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        kvStore = new InMemoryKVStore();
        memoryStore = new HybridMemoryStore(vectorStore, kvStore, null);
        config = MemoryConfig.builder()
            .triggerMode(MemoryConfig.RetrievalTriggerMode.AUTO)
            .autoRetrievalTopK(3)
            .autoRetrievalTimeout(Duration.ofMillis(100))
            .toolRetrievalTopK(10)
            .minStrength(0.1)
            .decayPolicy(new ExponentialDecayPolicy())
            .build();

        memoryManager = new MemoryManager(
            memoryStore,
            null, // extractor (requires LLM)
            null, // consolidator (requires LLM)
            null, // retriever (requires LLM)
            config
        );
    }

    @Test
    void testAddMemory() {
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("User prefers dark mode")
            .type(MemoryType.SEMANTIC)
            .importance(0.7)
            .build();

        memoryManager.addMemory(entry);

        var memories = memoryManager.getMemories("user-1", null, 10);
        assertEquals(1, memories.size());
        assertEquals("User prefers dark mode", memories.get(0).getContent());

        LOGGER.info("addMemory test passed");
    }

    @Test
    void testDeleteMemory() {
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("To be deleted")
            .type(MemoryType.SEMANTIC)
            .build();

        memoryManager.addMemory(entry);
        memoryManager.deleteMemory(entry.getId());

        var memories = memoryManager.getMemories("user-1", null, 10);
        assertTrue(memories.isEmpty());

        LOGGER.info("deleteMemory test passed");
    }

    @Test
    void testGetMemoriesByType() {
        var semantic = MemoryEntry.builder()
            .userId("user-1")
            .content("Semantic memory")
            .type(MemoryType.SEMANTIC)
            .build();
        var episodic = MemoryEntry.builder()
            .userId("user-1")
            .content("Episodic memory")
            .type(MemoryType.EPISODIC)
            .build();

        memoryManager.addMemory(semantic);
        memoryManager.addMemory(episodic);

        var semanticMemories = memoryManager.getMemories("user-1", MemoryType.SEMANTIC, 10);
        assertEquals(1, semanticMemories.size());
        assertEquals(MemoryType.SEMANTIC, semanticMemories.get(0).getType());

        var episodicMemories = memoryManager.getMemories("user-1", MemoryType.EPISODIC, 10);
        assertEquals(1, episodicMemories.size());
        assertEquals(MemoryType.EPISODIC, episodicMemories.get(0).getType());

        LOGGER.info("getMemoriesByType test passed");
    }

    @Test
    void testRetrieveWithoutRetriever() {
        // When retriever is null, retrieve should throw NullPointerException
        // This tests the expected behavior when no retriever is configured
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("User likes coffee in the morning")
            .type(MemoryType.SEMANTIC)
            .build();

        memoryManager.addMemory(entry);

        var options = RetrievalOptions.defaults().withTopK(5);

        // Expect exception since retriever is null
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> {
            memoryManager.retrieve("coffee preferences", "user-1", options);
        });

        LOGGER.info("retrieve without retriever test passed");
    }

    @Test
    void testAutoRetrieve() {
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("User prefers tea")
            .type(MemoryType.SEMANTIC)
            .strength(0.8)
            .build();

        memoryManager.addMemory(entry);

        var context = memoryManager.autoRetrieve("What drink does user prefer?", "user-1");

        assertNotNull(context);

        LOGGER.info("autoRetrieve test passed");
    }

    @Test
    void testDeepRetrieveWithoutRetriever() {
        // When retriever is null, deepRetrieve should throw NullPointerException
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("Detailed memory about user habits")
            .type(MemoryType.SEMANTIC)
            .build();

        memoryManager.addMemory(entry);

        var filter = MemoryFilter.forUser("user-1");

        // Expect exception since retriever is null
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> {
            memoryManager.deepRetrieve("user habits", "user-1", filter);
        });

        LOGGER.info("deepRetrieve without retriever test passed");
    }

    @Test
    void testApplyDecay() {
        var oldEntry = MemoryEntry.builder()
            .userId("user-1")
            .content("Old memory")
            .type(MemoryType.SEMANTIC)
            .strength(1.0)
            .createdAt(Instant.now().minus(60, ChronoUnit.DAYS))
            .lastAccessedAt(Instant.now().minus(60, ChronoUnit.DAYS))
            .build();

        memoryManager.addMemory(oldEntry);
        memoryManager.applyDecay();

        var memories = memoryManager.getMemories("user-1", null, 10);
        // Depending on decay, might be removed or have reduced strength
        if (!memories.isEmpty()) {
            assertTrue(memories.get(0).getStrength() < 1.0);
        }

        LOGGER.info("applyDecay test passed");
    }

    @Test
    void testConsolidateFromShortTerm() {
        var shortTermMemory = new ShortTermMemory();
        shortTermMemory.setSummary("User discussed project deadlines and prefers morning meetings");

        memoryManager.consolidateFromShortTerm(shortTermMemory, "user-1");

        var memories = memoryManager.getMemories("user-1", MemoryType.EPISODIC, 10);
        assertEquals(1, memories.size());
        assertTrue(memories.get(0).getContent().contains("Session summary"));

        LOGGER.info("consolidateFromShortTerm test passed");
    }

    @Test
    void testConsolidateFromShortTermNull() {
        // Should not throw
        memoryManager.consolidateFromShortTerm(null, "user-1");

        var memories = memoryManager.getMemories("user-1", null, 10);
        assertTrue(memories.isEmpty());

        LOGGER.info("consolidateFromShortTerm null test passed");
    }

    @Test
    void testConsolidateFromShortTermEmptySummary() {
        var shortTermMemory = new ShortTermMemory();
        shortTermMemory.setSummary("");

        memoryManager.consolidateFromShortTerm(shortTermMemory, "user-1");

        var memories = memoryManager.getMemories("user-1", null, 10);
        assertTrue(memories.isEmpty());

        LOGGER.info("consolidateFromShortTerm empty summary test passed");
    }

    @Test
    void testGetLongTermMemory() {
        assertNotNull(memoryManager.getLongTermMemory());
        assertEquals(memoryStore, memoryManager.getLongTermMemory());

        LOGGER.info("getLongTermMemory test passed");
    }

    @Test
    void testGetConfig() {
        assertNotNull(memoryManager.getConfig());
        assertEquals(config, memoryManager.getConfig());

        LOGGER.info("getConfig test passed");
    }

    @Test
    void testRetrieveEmptyQuery() {
        var options = RetrievalOptions.defaults();
        var context = memoryManager.retrieve("", "user-1", options);

        assertTrue(context.isEmpty());

        LOGGER.info("retrieve empty query test passed");
    }

    @Test
    void testRetrieveNullQuery() {
        var options = RetrievalOptions.defaults();
        var context = memoryManager.retrieve(null, "user-1", options);

        assertTrue(context.isEmpty());

        LOGGER.info("retrieve null query test passed");
    }

    @Test
    void testRetrieveWithNullOptions() {
        // When retriever is null, retrieve should throw NullPointerException
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .content("Test content")
            .type(MemoryType.SEMANTIC)
            .build();

        memoryManager.addMemory(entry);

        // Expect exception since retriever is null
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> {
            memoryManager.retrieve("test", "user-1", null);
        });

        LOGGER.info("retrieve with null options test passed");
    }

    @Test
    void testMemoryLimit() {
        for (int i = 0; i < 20; i++) {
            var entry = MemoryEntry.builder()
                .userId("user-1")
                .content("Memory " + i)
                .type(MemoryType.SEMANTIC)
                .build();
            memoryManager.addMemory(entry);
        }

        var memories = memoryManager.getMemories("user-1", null, 5);
        assertEquals(5, memories.size());

        var allMemories = memoryManager.getMemories("user-1", null, 100);
        assertEquals(20, allMemories.size());

        LOGGER.info("memory limit test passed");
    }
}
