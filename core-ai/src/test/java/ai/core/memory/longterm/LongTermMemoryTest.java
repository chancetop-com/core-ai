package ai.core.memory.longterm;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.extraction.LongTermMemoryCoordinator;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for long-term memory functionality.
 *
 * @author xander
 */
@SuppressWarnings("PMD.SingularField")
class LongTermMemoryTest {

    private static final String USER_ID = "user-123";
    private static final String SESSION_ID = "session-456";
    private static final int EMBEDDING_DIM = 8;

    private LongTermMemoryConfig config;
    private LongTermMemoryStore store;
    private MemoryExtractor extractor;
    private LLMProvider llmProvider;
    private LongTermMemoryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        config = LongTermMemoryConfig.builder()
            .metadataStoreType(LongTermMemoryConfig.MetadataStoreType.IN_MEMORY)
            .vectorStoreType(LongTermMemoryConfig.VectorStoreType.IN_MEMORY)
            .embeddingDimension(EMBEDDING_DIM)
            .maxBufferTurns(3)
            .maxBufferTokens(500)
            .extractOnSessionEnd(true)
            .asyncExtraction(false)
            .enableDecay(true)
            .build();

        store = new DefaultLongTermMemoryStore(config);
        extractor = createMockExtractor();
        llmProvider = createMockLLMProvider();
        coordinator = new LongTermMemoryCoordinator(store, extractor, llmProvider, config);
    }

    @Test
    void testBasicMemoryStorageAndRetrieval() {
        // 1. Manually save a memory
        MemoryRecord record = MemoryRecord.builder()
            .userId(USER_ID)
            .content("User is a Java developer who prefers clean code")
            .type(MemoryType.FACT)
            .build();

        float[] embedding = randomEmbedding();
        store.save(record, embedding);

        // 2. Verify storage
        assertEquals(1, store.count(USER_ID));

        // 3. Search for the memory
        List<MemoryRecord> results = store.search(USER_ID, embedding, 5);
        assertFalse(results.isEmpty());
        assertEquals("User is a Java developer who prefers clean code", results.get(0).getContent());
    }

    @Test
    void testMemoryIsolationByUserId() {
        // Save memories for different users
        MemoryRecord record1 = MemoryRecord.builder()
            .userId("user-A")
            .content("User A likes Python")
            .type(MemoryType.PREFERENCE)
            .build();

        MemoryRecord record2 = MemoryRecord.builder()
            .userId("user-B")
            .content("User B likes Java")
            .type(MemoryType.PREFERENCE)
            .build();

        store.save(record1, randomEmbedding());
        store.save(record2, randomEmbedding());

        // Verify isolation
        assertEquals(1, store.count("user-A"));
        assertEquals(1, store.count("user-B"));

        // Search should only return memories for the specific user
        List<MemoryRecord> resultsA = store.search("user-A", randomEmbedding(), 10);
        assertEquals(1, resultsA.size());
        assertTrue(resultsA.get(0).getContent().contains("User A"));
    }

    @Test
    void testCoordinatorExtractionOnSessionEnd() {
        // Initialize session
        coordinator.initSession(USER_ID, SESSION_ID);

        // Simulate conversation (not enough to trigger batch extraction)
        coordinator.onMessage(Message.of(RoleType.USER, "Hello, I'm a senior Java developer"));
        coordinator.onMessage(Message.of(RoleType.ASSISTANT, "Nice to meet you!"));

        // Verify no extraction yet (buffer size < maxBufferTurns)
        assertEquals(0, store.count(USER_ID));

        // End session - should trigger extraction
        coordinator.onSessionEnd();

        // Verify memories were extracted and stored
        assertTrue(store.count(USER_ID) > 0);
    }

    @Test
    void testCoordinatorBatchExtractionTrigger() {
        // Configure to trigger after 2 turns
        LongTermMemoryConfig batchConfig = LongTermMemoryConfig.builder()
            .maxBufferTurns(2)
            .asyncExtraction(false)
            .build();

        LongTermMemoryStore batchStore = new DefaultLongTermMemoryStore(batchConfig);
        LongTermMemoryCoordinator batchCoordinator = new LongTermMemoryCoordinator(
            batchStore, extractor, llmProvider, batchConfig);

        batchCoordinator.initSession(USER_ID, SESSION_ID);

        // First turn
        batchCoordinator.onMessage(Message.of(RoleType.USER, "I prefer using IntelliJ IDEA"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

        // Second turn - should trigger batch extraction
        batchCoordinator.onMessage(Message.of(RoleType.USER, "I also like Spring Boot"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Spring Boot is excellent!"));

        // Verify extraction was triggered
        assertTrue(batchStore.count(USER_ID) > 0);
    }

    @Test
    void testMemoryDecay() {
        // Create a memory with specific decay factor
        MemoryRecord record = MemoryRecord.builder()
            .userId(USER_ID)
            .content("Old memory that should decay")
            .type(MemoryType.EPISODE)
            .build();

        store.save(record, randomEmbedding());

        // Manually set low decay factor (simulating old memory)
        record.setDecayFactor(0.05);

        // Get decayed memories
        List<MemoryRecord> decayed = store.getDecayedMemories(USER_ID, 0.1);
        assertEquals(1, decayed.size());
    }

    @Test
    void testSearchWithFilter() {
        // Save different types of memories
        store.save(MemoryRecord.builder()
            .userId(USER_ID)
            .content("User's goal is to learn AI")
            .type(MemoryType.GOAL)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .userId(USER_ID)
            .content("User prefers dark mode")
            .type(MemoryType.PREFERENCE)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .userId(USER_ID)
            .content("User is 30 years old")
            .type(MemoryType.FACT)
            .build(), randomEmbedding());

        // Search with type filter
        SearchFilter goalFilter = SearchFilter.builder()
            .types(MemoryType.GOAL)
            .build();

        List<MemoryRecord> goals = store.search(USER_ID, randomEmbedding(), 10, goalFilter);
        assertEquals(1, goals.size());
        assertEquals(MemoryType.GOAL, goals.get(0).getType());
    }

    @Test
    void testEffectiveScoreCalculation() {
        MemoryRecord record = MemoryRecord.builder()
            .userId(USER_ID)
            .content("Test memory")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build();

        record.setDecayFactor(0.9);
        record.setAccessCount(5);

        double score = record.calculateEffectiveScore(0.95);

        // score = similarity(0.95) * importance(0.8) * decay(0.9) * frequencyBonus
        // frequencyBonus = 1.0 + 0.1 * ln(1 + 5) ≈ 1.179
        assertTrue(score > 0.6);
        assertTrue(score < 1.0);
    }

    // ==================== Helper Methods ====================

    private MemoryExtractor createMockExtractor() {
        return (namespace, messages) -> {
            List<MemoryRecord> records = new ArrayList<>();

            for (Message msg : messages) {
                if (msg.role == RoleType.USER && msg.content != null) {
                    // Simple extraction: create a FACT memory from user messages
                    MemoryRecord record = MemoryRecord.builder()
                        .namespace(namespace)
                        .content("User said: " + msg.content)
                        .type(MemoryType.FACT)
                        .build();
                    records.add(record);
                }
            }

            return records;
        };
    }

    private LLMProvider createMockLLMProvider() {
        LLMProvider mockProvider = mock(LLMProvider.class);

        when(mockProvider.embeddings(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<EmbeddingResponse.EmbeddingData> embeddings = new ArrayList<>();

            for (int i = 0; i < request.query().size(); i++) {
                float[] vec = randomEmbedding();
                embeddings.add(EmbeddingResponse.EmbeddingData.of(request.query().get(i), Embedding.of(vec)));
            }

            return EmbeddingResponse.of(embeddings, null);
        });

        return mockProvider;
    }

    private float[] randomEmbedding() {
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding[i] = (float) Math.random();
        }
        // Normalize
        float norm = 0;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] /= norm;
        }
        return embedding;
    }

    /**
     * Tests for LongTermMemory facade - the recommended way to use long-term memory with Agent.
     */
    @Nested
    @DisplayName("LongTermMemory Facade Tests")
    class LongTermMemoryFacadeTests {

        private LongTermMemory memory;

        @BeforeEach
        void setUpFacade() {
            LongTermMemoryConfig facadeConfig = LongTermMemoryConfig.builder()
                .maxBufferTurns(2)
                .asyncExtraction(false)
                .build();

            LongTermMemoryStore facadeStore = new DefaultLongTermMemoryStore(facadeConfig);

            memory = new LongTermMemory(facadeStore, extractor, llmProvider, facadeConfig);
        }

        @Test
        @DisplayName("Should work with session lifecycle")
        void testSessionLifecycle() {
            // Start session
            memory.startSession(USER_ID, SESSION_ID);
            assertEquals(USER_ID, memory.getCurrentUserId());
            assertEquals(SESSION_ID, memory.getCurrentSessionId());

            // Process messages
            memory.onMessage(Message.of(RoleType.USER, "I'm a Java developer"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Nice!"));
            memory.onMessage(Message.of(RoleType.USER, "I prefer IntelliJ"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

            // End session
            memory.endSession();

            // Verify memories were extracted
            assertTrue(memory.getMemoryCount() > 0);
        }

        @Test
        @DisplayName("Should recall memories after extraction")
        void testRecallAfterExtraction() {
            memory.startSession(USER_ID, SESSION_ID);

            // Add some messages
            memory.onMessage(Message.of(RoleType.USER, "I love Python programming"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Python is great!"));
            memory.onMessage(Message.of(RoleType.USER, "I also like machine learning"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Interesting field!"));

            memory.endSession();

            // Recall memories
            List<MemoryRecord> recalled = memory.recall("programming", 5);
            assertNotNull(recalled);
            // Should have extracted some memories
            assertTrue(memory.hasMemories());
        }

        @Test
        @DisplayName("Should format memories as context string")
        void testFormatAsContext() {
            // Manually add a memory for testing
            MemoryRecord record = MemoryRecord.builder()
                .userId(USER_ID)
                .content("User is a Java developer")
                .type(MemoryType.FACT)
                .build();
            memory.getStore().save(record, randomEmbedding());

            memory.startSession(USER_ID, SESSION_ID);

            List<MemoryRecord> memories = List.of(record);
            String context = memory.formatAsContext(memories);

            assertNotNull(context);
            assertTrue(context.contains("[User Memory]"));
            assertTrue(context.contains("Java developer"));
        }

        @Test
        @DisplayName("Should return empty context for empty memories")
        void testEmptyContext() {
            String context = memory.formatAsContext(List.of());
            assertEquals("", context);

            context = memory.formatAsContext(null);
            assertEquals("", context);
        }

        @Test
        @DisplayName("Should recall with type filter")
        void testRecallWithTypeFilter() {
            memory.startSession(USER_ID, SESSION_ID);

            // Add memories directly
            memory.getStore().save(MemoryRecord.builder()
                .userId(USER_ID)
                .content("User likes dark mode")
                .type(MemoryType.PREFERENCE)
                .build(), randomEmbedding());

            memory.getStore().save(MemoryRecord.builder()
                .userId(USER_ID)
                .content("User is 25 years old")
                .type(MemoryType.FACT)
                .build(), randomEmbedding());

            // Recall only preferences
            List<MemoryRecord> preferences = memory.recall("mode", 10, MemoryType.PREFERENCE);
            assertFalse(preferences.isEmpty());
            assertEquals(MemoryType.PREFERENCE, preferences.get(0).getType());
        }
    }
}
