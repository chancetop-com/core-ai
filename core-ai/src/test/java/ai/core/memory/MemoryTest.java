package ai.core.memory;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.memory.extraction.MemoryCoordinator;
import ai.core.memory.extraction.MemoryExtractor;
import ai.core.memory.history.ChatRecord;
import ai.core.memory.history.InMemoryChatHistoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
 * @author xander
 */
@SuppressWarnings("PMD.SingularField")
class MemoryTest {

    private static final String USER_ID = "user-456";
    private static final int EMBEDDING_DIM = 8;

    private MemoryStore store;
    private MemoryExtractor extractor;
    private LLMProvider llmProvider;
    private InMemoryChatHistoryProvider historyProvider;
    private MemoryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        MemoryConfig config = MemoryConfig.builder()
                .maxBufferTurns(3)
                .maxBufferTokens(500)
                .extractOnSessionEnd(true)
                .asyncExtraction(false)
                .enableDecay(true)
                .build();

        store = new InMemoryStore();
        historyProvider = new InMemoryChatHistoryProvider();
        extractor = createMockExtractor();
        llmProvider = createMockLLMProvider();
        coordinator = new MemoryCoordinator(store, historyProvider, extractor, llmProvider, config);
    }

    @Test
    void testBasicMemoryStorageAndRetrieval() {
        MemoryRecord record = MemoryRecord.builder()
            .content("User is a Java developer who prefers clean code")
            .importance(0.8)
            .build();

        List<Double> embedding = randomEmbedding();
        store.save(USER_ID, record, embedding);

        assertEquals(1, store.count(USER_ID));

        List<MemoryRecord> results = store.searchByVector(USER_ID, embedding, 5);
        assertFalse(results.isEmpty());
        assertEquals("User is a Java developer who prefers clean code", results.getFirst().getContent());
    }

    @Test
    void testCoordinatorExtraction() {
        // Add records to history provider
        historyProvider.addRecord(USER_ID, ChatRecord.user("Hello, I'm a senior Java developer", Instant.now()));
        historyProvider.addRecord(USER_ID, ChatRecord.assistant("Nice to meet you!", Instant.now()));

        assertEquals(0, store.count(USER_ID));

        // Trigger extraction manually
        coordinator.extractFromHistory(USER_ID);

        assertTrue(store.count(USER_ID) > 0);
    }

    @Test
    void testCoordinatorBatchExtractionTrigger() {
        MemoryConfig batchConfig = MemoryConfig.builder()
            .maxBufferTurns(2)
            .asyncExtraction(false)
            .build();

        MemoryStore batchStore = new InMemoryStore();
        InMemoryChatHistoryProvider batchHistoryProvider = new InMemoryChatHistoryProvider();
        MemoryCoordinator batchCoordinator = new MemoryCoordinator(
            batchStore, batchHistoryProvider, extractor, llmProvider, batchConfig);

        // Add records to history
        batchHistoryProvider.addRecord(USER_ID, ChatRecord.user("I prefer using IntelliJ IDEA", Instant.now()));
        batchHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Great choice!", Instant.now()));
        batchHistoryProvider.addRecord(USER_ID, ChatRecord.user("I also like Spring Boot", Instant.now()));
        batchHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Spring Boot is excellent!", Instant.now()));

        // Check if should extract (2 user turns >= maxBufferTurns of 2)
        assertTrue(batchCoordinator.shouldExtract(USER_ID));

        // Trigger extraction
        batchCoordinator.extractIfNeeded(USER_ID);

        assertTrue(batchStore.count(USER_ID) > 0);
    }

    @Test
    void testMemoryDecay() {
        MemoryRecord record = MemoryRecord.builder()
            .content("Old memory that should decay")
            .importance(0.6)
            .build();

        store.save(USER_ID, record, randomEmbedding());

        store.updateDecayFactor(USER_ID, record.getId(), 0.05);

        List<MemoryRecord> decayed = store.findDecayed(USER_ID, 0.1);
        assertEquals(1, decayed.size());
    }

    @Test
    void testSearchWithImportanceFilter() {
        store.save(USER_ID, MemoryRecord.builder()
            .content("High importance memory")
            .importance(0.9)
            .build(), randomEmbedding());

        store.save(USER_ID, MemoryRecord.builder()
            .content("Medium importance memory")
            .importance(0.7)
            .build(), randomEmbedding());

        store.save(USER_ID, MemoryRecord.builder()
            .content("Low importance memory")
            .importance(0.5)
            .build(), randomEmbedding());

        SearchFilter highImportanceFilter = SearchFilter.builder()
            .minImportance(0.8)
            .build();

        List<MemoryRecord> results = store.searchByVector(USER_ID, randomEmbedding(), 10, highImportanceFilter);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().getImportance() >= 0.8);
    }

    @Test
    void testEffectiveScoreCalculation() {
        MemoryRecord record = MemoryRecord.builder()
            .content("Test memory")
            .importance(0.8)
            .build();

        record.setDecayFactor(0.9);
        record.setAccessCount(5);

        double score = record.calculateEffectiveScore(0.95);

        // score = similarity(0.95) * importance(0.8) * decay(0.9) * frequencyBonus
        assertTrue(score > 0.6);
        assertTrue(score < 1.0);
    }

    // ==================== Helper Methods ====================

    private MemoryExtractor createMockExtractor() {
        return records -> {
            List<MemoryRecord> memoryRecords = new ArrayList<>();

            for (ChatRecord record : records) {
                if (record.role() == RoleType.USER && record.content() != null) {
                    MemoryRecord memRecord = MemoryRecord.builder()
                        .content("User said: " + record.content())
                        .importance(0.7)
                        .build();
                    memoryRecords.add(memRecord);
                }
            }

            return memoryRecords;
        };
    }

    private LLMProvider createMockLLMProvider() {
        LLMProvider mockProvider = mock(LLMProvider.class);

        when(mockProvider.embeddings(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<EmbeddingResponse.EmbeddingData> embeddings = new ArrayList<>();

            for (int i = 0; i < request.query().size(); i++) {
                float[] vec = new float[EMBEDDING_DIM];
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    vec[j] = (float) Math.random();
                }
                embeddings.add(EmbeddingResponse.EmbeddingData.of(request.query().get(i), Embedding.of(vec)));
            }

            return EmbeddingResponse.of(embeddings, null);
        });

        return mockProvider;
    }

    private List<Double> randomEmbedding() {
        List<Double> embedding = new ArrayList<>();
        double norm = 0;
        double[] values = new double[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            values[i] = Math.random();
            norm += values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding.add(values[i] / norm);
        }
        return embedding;
    }

    @Nested
    @DisplayName("Memory Facade Tests")
    class MemoryFacadeTests {

        private Memory memory;
        private InMemoryChatHistoryProvider facadeHistoryProvider;

        @BeforeEach
        void setUpFacade() {
            MemoryConfig facadeConfig = MemoryConfig.builder()
                .maxBufferTurns(2)
                .asyncExtraction(false)
                .build();

            MemoryStore facadeStore = new InMemoryStore();
            facadeHistoryProvider = new InMemoryChatHistoryProvider();

            memory = new Memory(facadeStore, facadeHistoryProvider, extractor, llmProvider, facadeConfig);
        }

        @Test
        @DisplayName("Should extract memories from history")
        void testExtractFromHistory() {
            // Add records to history
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.user("I'm a Java developer", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Nice!", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.user("I prefer IntelliJ", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Great choice!", Instant.now()));

            // Extract memories
            memory.extract(USER_ID);

            assertTrue(memory.getMemoryCount(USER_ID) > 0);
        }

        @Test
        @DisplayName("Should retrieve memories after extraction")
        void testRetrieveAfterExtraction() {
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.user("I love Python programming", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Python is great!", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.user("I also like machine learning", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Interesting field!", Instant.now()));

            memory.extract(USER_ID);

            List<MemoryRecord> retrieved = memory.retrieve(USER_ID, "programming", 5);
            assertNotNull(retrieved);
            assertTrue(memory.hasMemories(USER_ID));
        }

        @Test
        @DisplayName("Should format memories as context string")
        void testFormatAsContext() {
            MemoryRecord record = MemoryRecord.builder()
                .content("User is a Java developer")
                .importance(0.8)
                .build();
            memory.getStore().save(USER_ID, record, randomEmbedding());

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
        @DisplayName("Should track extraction state")
        void testExtractionStateTracking() {
            assertEquals(-1, memory.getLastExtractedIndex(USER_ID));

            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.user("Hello", Instant.now()));
            facadeHistoryProvider.addRecord(USER_ID, ChatRecord.assistant("Hi!", Instant.now()));

            memory.extract(USER_ID);

            assertTrue(memory.getLastExtractedIndex(USER_ID) >= 0);

            // Reset and verify
            memory.resetExtractionState(USER_ID);
            assertEquals(-1, memory.getLastExtractedIndex(USER_ID));
        }
    }

    @Nested
    @DisplayName("Decay Calculator Tests")
    class DecayCalculatorTests {

        @Test
        @DisplayName("Should return 1.0 for newly created memory")
        void testNewMemoryDecay() {
            MemoryRecord record = MemoryRecord.builder()
                .content("Fresh memory")
                .importance(0.7)
                .build();

            double decay = DecayCalculator.calculate(record);
            assertEquals(1.0, decay, 0.01);
        }

        @Test
        @DisplayName("Should handle null record gracefully")
        void testNullRecordDecay() {
            double decay = DecayCalculator.calculate(null);
            assertEquals(1.0, decay, 0.01);
        }
    }

    @Nested
    @DisplayName("Search Filter Tests")
    class SearchFilterTests {

        @BeforeEach
        void setUpFilterTests() {
            store.save(USER_ID, MemoryRecord.builder()
                .content("User wants to learn Rust")
                .importance(0.9)
                .build(), randomEmbedding());

            store.save(USER_ID, MemoryRecord.builder()
                .content("User prefers vim editor")
                .importance(0.7)
                .build(), randomEmbedding());

            store.save(USER_ID, MemoryRecord.builder()
                .content("User is from Beijing")
                .importance(0.5)
                .build(), randomEmbedding());
        }

        @Test
        @DisplayName("Should filter by minimum importance")
        void testMinImportanceFilter() {
            SearchFilter filter = SearchFilter.builder()
                .minImportance(0.8)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_ID, randomEmbedding(), 10, filter);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().getImportance() >= 0.8);
        }

        @Test
        @DisplayName("Should filter by minimum decay factor")
        void testMinDecayFactorFilter() {
            SearchFilter filter = SearchFilter.builder()
                .minDecayFactor(0.5)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_ID, randomEmbedding(), 10, filter);
            assertEquals(3, results.size());
        }
    }

    @Nested
    @DisplayName("InMemoryStore Tests")
    class InMemoryStoreTests {

        @Test
        @DisplayName("Should create InMemoryStore")
        void testCreateInMemoryStore() {
            MemoryStore memStore = new InMemoryStore();
            assertNotNull(memStore);
        }

        @Test
        @DisplayName("Should save and retrieve records")
        void testSaveAndRetrieve() {
            MemoryStore memStore = new InMemoryStore();

            MemoryRecord record = MemoryRecord.builder()
                .content("Test content")
                .importance(0.7)
                .build();

            memStore.save(USER_ID, record, randomEmbedding());
            assertEquals(1, memStore.count(USER_ID));

            var found = memStore.findById(USER_ID, record.getId());
            assertTrue(found.isPresent());
            assertEquals("Test content", found.get().getContent());
        }

        @Test
        @DisplayName("Should delete records")
        void testDelete() {
            MemoryStore memStore = new InMemoryStore();

            MemoryRecord record = MemoryRecord.builder()
                .content("To be deleted")
                .importance(0.7)
                .build();

            memStore.save(USER_ID, record, randomEmbedding());
            assertEquals(1, memStore.count(USER_ID));

            memStore.delete(USER_ID, record.getId());
            assertEquals(0, memStore.count(USER_ID));
        }
    }
}
