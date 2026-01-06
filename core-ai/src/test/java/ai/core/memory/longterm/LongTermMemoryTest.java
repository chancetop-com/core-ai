package ai.core.memory.longterm;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.history.ChatHistoryStore;
import ai.core.memory.history.InMemoryChatHistoryStore;
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
 * @author xander
 */
@SuppressWarnings("PMD.SingularField")
class LongTermMemoryTest {

    private static final String SESSION_ID = "session-456";
    private static final int EMBEDDING_DIM = 8;

    private MemoryStore store;
    private MemoryExtractor extractor;
    private LLMProvider llmProvider;
    private LongTermMemoryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        LongTermMemoryConfig config = LongTermMemoryConfig.builder()
                .maxBufferTurns(3)
                .maxBufferTokens(500)
                .extractOnSessionEnd(true)
                .asyncExtraction(false)
                .enableDecay(true)
                .build();

        store = new InMemoryStore();
        ChatHistoryStore chatHistoryStore = new InMemoryChatHistoryStore();
        extractor = createMockExtractor();
        llmProvider = createMockLLMProvider();
        coordinator = new LongTermMemoryCoordinator(store, chatHistoryStore, extractor, llmProvider, config);
    }

    @Test
    void testBasicMemoryStorageAndRetrieval() {
        MemoryRecord record = MemoryRecord.builder()
            .content("User is a Java developer who prefers clean code")
            .importance(0.8)
            .build();

        List<Double> embedding = randomEmbedding();
        store.save(record, embedding);

        assertEquals(1, store.count());

        List<MemoryRecord> results = store.searchByVector(embedding, 5);
        assertFalse(results.isEmpty());
        assertEquals("User is a Java developer who prefers clean code", results.getFirst().getContent());
    }

    @Test
    void testCoordinatorExtractionOnSessionEnd() {
        coordinator.initSession(SESSION_ID);

        coordinator.onMessage(Message.of(RoleType.USER, "Hello, I'm a senior Java developer"));
        coordinator.onMessage(Message.of(RoleType.ASSISTANT, "Nice to meet you!"));

        assertEquals(0, store.count());

        coordinator.onSessionEnd();

        assertTrue(store.count() > 0);
    }

    @Test
    void testCoordinatorBatchExtractionTrigger() {
        LongTermMemoryConfig batchConfig = LongTermMemoryConfig.builder()
            .maxBufferTurns(2)
            .asyncExtraction(false)
            .build();

        MemoryStore batchStore = new InMemoryStore();
        ChatHistoryStore batchChatHistoryStore = new InMemoryChatHistoryStore();
        LongTermMemoryCoordinator batchCoordinator = new LongTermMemoryCoordinator(
            batchStore, batchChatHistoryStore, extractor, llmProvider, batchConfig);

        batchCoordinator.initSession(SESSION_ID);

        batchCoordinator.onMessage(Message.of(RoleType.USER, "I prefer using IntelliJ IDEA"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

        batchCoordinator.onMessage(Message.of(RoleType.USER, "I also like Spring Boot"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Spring Boot is excellent!"));

        assertTrue(batchStore.count() > 0);
    }

    @Test
    void testMemoryDecay() {
        MemoryRecord record = MemoryRecord.builder()
            .content("Old memory that should decay")
            .importance(0.6)
            .build();

        store.save(record, randomEmbedding());

        store.updateDecayFactor(record.getId(), 0.05);

        List<MemoryRecord> decayed = store.findDecayed(0.1);
        assertEquals(1, decayed.size());
    }

    @Test
    void testSearchWithImportanceFilter() {
        store.save(MemoryRecord.builder()
            .content("High importance memory")
            .importance(0.9)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .content("Medium importance memory")
            .importance(0.7)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .content("Low importance memory")
            .importance(0.5)
            .build(), randomEmbedding());

        SearchFilter highImportanceFilter = SearchFilter.builder()
            .minImportance(0.8)
            .build();

        List<MemoryRecord> results = store.searchByVector(randomEmbedding(), 10, highImportanceFilter);
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
        return messages -> {
            List<MemoryRecord> records = new ArrayList<>();

            for (Message msg : messages) {
                if (msg.role == RoleType.USER && msg.content != null) {
                    MemoryRecord record = MemoryRecord.builder()
                        .content("User said: " + msg.content)
                        .importance(0.7)
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
    @DisplayName("LongTermMemory Facade Tests")
    class LongTermMemoryFacadeTests {

        private LongTermMemory memory;

        @BeforeEach
        void setUpFacade() {
            LongTermMemoryConfig facadeConfig = LongTermMemoryConfig.builder()
                .maxBufferTurns(2)
                .asyncExtraction(false)
                .build();

            MemoryStore facadeStore = new InMemoryStore();
            ChatHistoryStore facadeChatHistoryStore = new InMemoryChatHistoryStore();

            memory = new LongTermMemory(facadeStore, facadeChatHistoryStore, extractor, llmProvider, facadeConfig);
        }

        @Test
        @DisplayName("Should work with session lifecycle")
        void testSessionLifecycle() {
            memory.startSession(SESSION_ID);
            assertEquals(SESSION_ID, memory.getCurrentSessionId());

            memory.onMessage(Message.of(RoleType.USER, "I'm a Java developer"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Nice!"));
            memory.onMessage(Message.of(RoleType.USER, "I prefer IntelliJ"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

            memory.endSession();

            assertTrue(memory.getMemoryCount() > 0);
        }

        @Test
        @DisplayName("Should recall memories after extraction")
        void testRecallAfterExtraction() {
            memory.startSession(SESSION_ID);

            memory.onMessage(Message.of(RoleType.USER, "I love Python programming"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Python is great!"));
            memory.onMessage(Message.of(RoleType.USER, "I also like machine learning"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Interesting field!"));

            memory.endSession();

            List<MemoryRecord> recalled = memory.recall("programming", 5);
            assertNotNull(recalled);
            assertTrue(memory.hasMemories());
        }

        @Test
        @DisplayName("Should format memories as context string")
        void testFormatAsContext() {
            MemoryRecord record = MemoryRecord.builder()
                .content("User is a Java developer")
                .importance(0.8)
                .build();
            memory.getStore().save(record, randomEmbedding());

            memory.startSession(SESSION_ID);

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
            store.save(MemoryRecord.builder()
                .content("User wants to learn Rust")
                .importance(0.9)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .content("User prefers vim editor")
                .importance(0.7)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
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

            List<MemoryRecord> results = store.searchByVector(randomEmbedding(), 10, filter);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().getImportance() >= 0.8);
        }

        @Test
        @DisplayName("Should filter by minimum decay factor")
        void testMinDecayFactorFilter() {
            SearchFilter filter = SearchFilter.builder()
                .minDecayFactor(0.5)
                .build();

            List<MemoryRecord> results = store.searchByVector(randomEmbedding(), 10, filter);
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

            memStore.save(record, randomEmbedding());
            assertEquals(1, memStore.count());

            var found = memStore.findById(record.getId());
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

            memStore.save(record, randomEmbedding());
            assertEquals(1, memStore.count());

            memStore.delete(record.getId());
            assertEquals(0, memStore.count());
        }
    }
}
