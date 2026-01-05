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
    private static final MemoryScope USER_SCOPE = MemoryScope.forUser(USER_ID);

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
        extractor = createMockExtractor();
        llmProvider = createMockLLMProvider();
        coordinator = new LongTermMemoryCoordinator(store, extractor, llmProvider, config);
    }

    @Test
    void testBasicMemoryStorageAndRetrieval() {
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("User is a Java developer who prefers clean code")
            .importance(0.8)
            .build();

        List<Double> embedding = randomEmbedding();
        store.save(record, embedding);

        assertEquals(1, store.count(USER_SCOPE));

        List<MemoryRecord> results = store.searchByVector(USER_SCOPE, embedding, 5);
        assertFalse(results.isEmpty());
        assertEquals("User is a Java developer who prefers clean code", results.getFirst().getContent());
    }

    @Test
    void testMemoryIsolationByUserId() {
        MemoryScope scopeA = MemoryScope.forUser("user-A");
        MemoryScope scopeB = MemoryScope.forUser("user-B");

        MemoryRecord record1 = MemoryRecord.builder()
            .scope(scopeA)
            .content("User A likes Python")
            .importance(0.8)
            .build();

        MemoryRecord record2 = MemoryRecord.builder()
            .scope(scopeB)
            .content("User B likes Java")
            .importance(0.8)
            .build();

        store.save(record1, randomEmbedding());
        store.save(record2, randomEmbedding());

        assertEquals(1, store.count(scopeA));
        assertEquals(1, store.count(scopeB));

        List<MemoryRecord> resultsA = store.searchByVector(scopeA, randomEmbedding(), 10);
        assertEquals(1, resultsA.size());
        assertTrue(resultsA.getFirst().getContent().contains("User A"));
    }

    @Test
    void testCoordinatorExtractionOnSessionEnd() {
        coordinator.initSession(USER_SCOPE, SESSION_ID);

        coordinator.onMessage(Message.of(RoleType.USER, "Hello, I'm a senior Java developer"));
        coordinator.onMessage(Message.of(RoleType.ASSISTANT, "Nice to meet you!"));

        assertEquals(0, store.count(USER_SCOPE));

        coordinator.onSessionEnd();

        assertTrue(store.count(USER_SCOPE) > 0);
    }

    @Test
    void testCoordinatorBatchExtractionTrigger() {
        LongTermMemoryConfig batchConfig = LongTermMemoryConfig.builder()
            .maxBufferTurns(2)
            .asyncExtraction(false)
            .build();

        MemoryStore batchStore = new InMemoryStore();
        LongTermMemoryCoordinator batchCoordinator = new LongTermMemoryCoordinator(
            batchStore, extractor, llmProvider, batchConfig);

        batchCoordinator.initSession(USER_SCOPE, SESSION_ID);

        batchCoordinator.onMessage(Message.of(RoleType.USER, "I prefer using IntelliJ IDEA"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

        batchCoordinator.onMessage(Message.of(RoleType.USER, "I also like Spring Boot"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Spring Boot is excellent!"));

        assertTrue(batchStore.count(USER_SCOPE) > 0);
    }

    @Test
    void testMemoryDecay() {
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("Old memory that should decay")
            .importance(0.6)
            .build();

        store.save(record, randomEmbedding());

        store.updateDecayFactor(record.getId(), 0.05);

        List<MemoryRecord> decayed = store.findDecayed(USER_SCOPE, 0.1);
        assertEquals(1, decayed.size());
    }

    @Test
    void testSearchWithImportanceFilter() {
        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("High importance memory")
            .importance(0.9)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("Medium importance memory")
            .importance(0.7)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("Low importance memory")
            .importance(0.5)
            .build(), randomEmbedding());

        SearchFilter highImportanceFilter = SearchFilter.builder()
            .minImportance(0.8)
            .build();

        List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, highImportanceFilter);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().getImportance() >= 0.8);
    }

    @Test
    void testEffectiveScoreCalculation() {
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
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
        return (namespace, messages) -> {
            List<MemoryRecord> records = new ArrayList<>();

            for (Message msg : messages) {
                if (msg.role == RoleType.USER && msg.content != null) {
                    MemoryRecord record = MemoryRecord.builder()
                        .scope(namespace)
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

            memory = new LongTermMemory(facadeStore, extractor, llmProvider, facadeConfig);
        }

        @Test
        @DisplayName("Should work with session lifecycle")
        void testSessionLifecycle() {
            MemoryScope expectedScope = MemoryScope.forUser(USER_ID);
            memory.startSession(expectedScope, SESSION_ID);
            assertEquals(expectedScope, memory.getCurrentScope());
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
            memory.startSession(MemoryScope.forUser(USER_ID), SESSION_ID);

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
                .scope(USER_SCOPE)
                .content("User is a Java developer")
                .importance(0.8)
                .build();
            memory.getStore().save(record, randomEmbedding());

            memory.startSession(MemoryScope.forUser(USER_ID), SESSION_ID);

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
    @DisplayName("MemoryScope Tests")
    class MemoryScopeTests {

        @Test
        @DisplayName("Should create scope with different dimensions")
        void testScopeCreation() {
            MemoryScope userScope = MemoryScope.forUser("alice");
            assertEquals("u:alice", userScope.toKey());
            assertTrue(userScope.hasUserId());
            assertFalse(userScope.hasSessionId());

            MemoryScope sessionScope = MemoryScope.forSession("alice", "sess-001");
            assertEquals("u:alice/s:sess-001", sessionScope.toKey());
            assertTrue(sessionScope.hasUserId());
            assertTrue(sessionScope.hasSessionId());

            MemoryScope agentScope = MemoryScope.forAgent("alice", "assistant");
            assertEquals("u:alice/a:assistant", agentScope.toKey());
            assertTrue(agentScope.hasAgentName());
        }

        @Test
        @DisplayName("Should isolate memories by scope")
        void testScopeIsolation() {
            MemoryScope scopeA = MemoryScope.forUser("user-A");
            MemoryScope scopeB = MemoryScope.forUser("user-B");

            store.save(MemoryRecord.builder()
                .scope(scopeA)
                .content("UserA prefers Java")
                .importance(0.8)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(scopeB)
                .content("UserB prefers Python")
                .importance(0.8)
                .build(), randomEmbedding());

            assertEquals(1, store.count(scopeA));
            assertEquals(1, store.count(scopeB));

            List<MemoryRecord> results = store.searchByVector(scopeA, randomEmbedding(), 10);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().getContent().contains("UserA"));
        }

        @Test
        @DisplayName("Should support scope matching")
        void testScopeMatching() {
            MemoryScope userScope = MemoryScope.forUser("alice");
            MemoryScope sessionScope = MemoryScope.forSession("alice", "sess-001");
            MemoryScope fullScope = MemoryScope.of("alice", "sess-001", "assistant");

            assertTrue(userScope.matches(sessionScope));
            assertTrue(userScope.matches(fullScope));
            assertTrue(sessionScope.matches(fullScope));

            MemoryScope otherUser = MemoryScope.forUser("bob");
            assertFalse(userScope.matches(otherUser));
        }
    }

    @Nested
    @DisplayName("Decay Calculator Tests")
    class DecayCalculatorTests {

        @Test
        @DisplayName("Should return 1.0 for newly created memory")
        void testNewMemoryDecay() {
            MemoryRecord record = MemoryRecord.builder()
                .scope(USER_SCOPE)
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
                .scope(USER_SCOPE)
                .content("User wants to learn Rust")
                .importance(0.9)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User prefers vim editor")
                .importance(0.7)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
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

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().getImportance() >= 0.8);
        }

        @Test
        @DisplayName("Should filter by minimum decay factor")
        void testMinDecayFactorFilter() {
            SearchFilter filter = SearchFilter.builder()
                .minDecayFactor(0.5)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
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
                .scope(USER_SCOPE)
                .content("Test content")
                .importance(0.7)
                .build();

            memStore.save(record, randomEmbedding());
            assertEquals(1, memStore.count(USER_SCOPE));

            var found = memStore.findById(record.getId());
            assertTrue(found.isPresent());
            assertEquals("Test content", found.get().getContent());
        }

        @Test
        @DisplayName("Should delete records")
        void testDelete() {
            MemoryStore memStore = new InMemoryStore();

            MemoryRecord record = MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("To be deleted")
                .importance(0.7)
                .build();

            memStore.save(record, randomEmbedding());
            assertEquals(1, memStore.count(USER_SCOPE));

            memStore.delete(record.getId());
            assertEquals(0, memStore.count(USER_SCOPE));
        }
    }
}
