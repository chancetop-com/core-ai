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
        // 1. Manually save a memory
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("User is a Java developer who prefers clean code")
            .type(MemoryType.FACT)
            .build();

        List<Double> embedding = randomEmbedding();
        store.save(record, embedding);

        // 2. Verify storage
        assertEquals(1, store.count(USER_SCOPE));

        // 3. Search for the memory
        List<MemoryRecord> results = store.searchByVector(USER_SCOPE, embedding, 5);
        assertFalse(results.isEmpty());
        assertEquals("User is a Java developer who prefers clean code", results.getFirst().getContent());
    }

    @Test
    void testMemoryIsolationByUserId() {
        // Save memories for different users
        MemoryScope scopeA = MemoryScope.forUser("user-A");
        MemoryScope scopeB = MemoryScope.forUser("user-B");

        MemoryRecord record1 = MemoryRecord.builder()
            .scope(scopeA)
            .content("User A likes Python")
            .type(MemoryType.PREFERENCE)
            .build();

        MemoryRecord record2 = MemoryRecord.builder()
            .scope(scopeB)
            .content("User B likes Java")
            .type(MemoryType.PREFERENCE)
            .build();

        store.save(record1, randomEmbedding());
        store.save(record2, randomEmbedding());

        // Verify isolation
        assertEquals(1, store.count(scopeA));
        assertEquals(1, store.count(scopeB));

        // Search should only return memories for the specific user
        List<MemoryRecord> resultsA = store.searchByVector(scopeA, randomEmbedding(), 10);
        assertEquals(1, resultsA.size());
        assertTrue(resultsA.getFirst().getContent().contains("User A"));
    }

    @Test
    void testCoordinatorExtractionOnSessionEnd() {
        // Initialize session
        coordinator.initSession(USER_SCOPE, SESSION_ID);

        // Simulate conversation (not enough to trigger batch extraction)
        coordinator.onMessage(Message.of(RoleType.USER, "Hello, I'm a senior Java developer"));
        coordinator.onMessage(Message.of(RoleType.ASSISTANT, "Nice to meet you!"));

        // Verify no extraction yet (buffer size < maxBufferTurns)
        assertEquals(0, store.count(USER_SCOPE));

        // End session - should trigger extraction
        coordinator.onSessionEnd();

        // Verify memories were extracted and stored
        assertTrue(store.count(USER_SCOPE) > 0);
    }

    @Test
    void testCoordinatorBatchExtractionTrigger() {
        // Configure to trigger after 2 turns
        LongTermMemoryConfig batchConfig = LongTermMemoryConfig.builder()
            .maxBufferTurns(2)
            .asyncExtraction(false)
            .build();

        MemoryStore batchStore = new InMemoryStore();
        LongTermMemoryCoordinator batchCoordinator = new LongTermMemoryCoordinator(
            batchStore, extractor, llmProvider, batchConfig);

        batchCoordinator.initSession(USER_SCOPE, SESSION_ID);

        // First turn
        batchCoordinator.onMessage(Message.of(RoleType.USER, "I prefer using IntelliJ IDEA"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Great choice!"));

        // Second turn - should trigger batch extraction
        batchCoordinator.onMessage(Message.of(RoleType.USER, "I also like Spring Boot"));
        batchCoordinator.onMessage(Message.of(RoleType.ASSISTANT, "Spring Boot is excellent!"));

        // Verify extraction was triggered
        assertTrue(batchStore.count(USER_SCOPE) > 0);
    }

    @Test
    void testMemoryDecay() {
        // Create a memory with specific decay factor
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("Old memory that should decay")
            .type(MemoryType.EPISODE)
            .build();

        store.save(record, randomEmbedding());

        // Manually set low decay factor (simulating old memory)
        store.updateDecayFactor(record.getId(), 0.05);

        // Get decayed memories
        List<MemoryRecord> decayed = store.findDecayed(USER_SCOPE, 0.1);
        assertEquals(1, decayed.size());
    }

    @Test
    void testSearchWithFilter() {
        // Save different types of memories
        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("User's goal is to learn AI")
            .type(MemoryType.GOAL)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("User prefers dark mode")
            .type(MemoryType.PREFERENCE)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(USER_SCOPE)
            .content("User is 30 years old")
            .type(MemoryType.FACT)
            .build(), randomEmbedding());

        // Search with type filter
        SearchFilter goalFilter = SearchFilter.builder()
            .types(MemoryType.GOAL)
            .build();

        List<MemoryRecord> goals = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, goalFilter);
        assertEquals(1, goals.size());
        assertEquals(MemoryType.GOAL, goals.getFirst().getType());
    }

    @Test
    void testEffectiveScoreCalculation() {
        MemoryRecord record = MemoryRecord.builder()
            .scope(USER_SCOPE)
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
                        .scope(namespace)
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

            MemoryStore facadeStore = new InMemoryStore();

            memory = new LongTermMemory(facadeStore, extractor, llmProvider, facadeConfig);
        }

        @Test
        @DisplayName("Should work with session lifecycle")
        void testSessionLifecycle() {
            // Start session
            MemoryScope expectedScope = MemoryScope.forUser(USER_ID);
            memory.startSession(expectedScope, SESSION_ID);
            assertEquals(expectedScope, memory.getCurrentScope());
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
            memory.startSession(MemoryScope.forUser(USER_ID), SESSION_ID);

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
                .scope(USER_SCOPE)
                .content("User is a Java developer")
                .type(MemoryType.FACT)
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

        @Test
        @DisplayName("Should recall with type filter")
        void testRecallWithTypeFilter() {
            memory.startSession(MemoryScope.forUser(USER_ID), SESSION_ID);

            // Add memories directly
            memory.getStore().save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User likes dark mode")
                .type(MemoryType.PREFERENCE)
                .build(), randomEmbedding());

            memory.getStore().save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User is 25 years old")
                .type(MemoryType.FACT)
                .build(), randomEmbedding());

            // Recall only preferences
            List<MemoryRecord> preferences = memory.recall("mode", 10, MemoryType.PREFERENCE);
            assertFalse(preferences.isEmpty());
            assertEquals(MemoryType.PREFERENCE, preferences.getFirst().getType());
        }
    }

    /**
     * Tests for MemoryScope functionality.
     */
    @Nested
    @DisplayName("MemoryScope Tests")
    class MemoryScopeTests {

        @Test
        @DisplayName("Should create scope with different dimensions")
        void testScopeCreation() {
            // User-scoped
            MemoryScope userScope = MemoryScope.forUser("alice");
            assertEquals("u:alice", userScope.toKey());
            assertTrue(userScope.hasUserId());
            assertFalse(userScope.hasSessionId());

            // Session-scoped
            MemoryScope sessionScope = MemoryScope.forSession("alice", "sess-001");
            assertEquals("u:alice/s:sess-001", sessionScope.toKey());
            assertTrue(sessionScope.hasUserId());
            assertTrue(sessionScope.hasSessionId());

            // Agent-scoped
            MemoryScope agentScope = MemoryScope.forAgent("alice", "assistant");
            assertEquals("u:alice/a:assistant", agentScope.toKey());
            assertTrue(agentScope.hasAgentName());
        }

        @Test
        @DisplayName("Should isolate memories by scope")
        void testScopeIsolation() {
            // User A
            MemoryScope scopeA = MemoryScope.forUser("user-A");
            // User B
            MemoryScope scopeB = MemoryScope.forUser("user-B");

            // Save memories in different scopes
            store.save(MemoryRecord.builder()
                .scope(scopeA)
                .content("UserA prefers Java")
                .type(MemoryType.PREFERENCE)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(scopeB)
                .content("UserB prefers Python")
                .type(MemoryType.PREFERENCE)
                .build(), randomEmbedding());

            // Verify isolation
            assertEquals(1, store.count(scopeA));
            assertEquals(1, store.count(scopeB));

            // Search should only return memories from specific scope
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

            // User scope matches session scope (userId matches)
            assertTrue(userScope.matches(sessionScope));
            assertTrue(userScope.matches(fullScope));

            // Session scope should match full scope
            assertTrue(sessionScope.matches(fullScope));

            // Different user should not match
            MemoryScope otherUser = MemoryScope.forUser("bob");
            assertFalse(userScope.matches(otherUser));
        }
    }

    /**
     * Tests for DecayCalculator functionality.
     */
    @Nested
    @DisplayName("Decay Calculator Tests")
    class DecayCalculatorTests {

        @Test
        @DisplayName("Should return 1.0 for newly created memory")
        void testNewMemoryDecay() {
            MemoryRecord record = MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("Fresh memory")
                .type(MemoryType.FACT)
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

        @Test
        @DisplayName("Should apply different decay rates by memory type")
        void testDecayRatesByType() {
            // EPISODE has higher decay rate (0.05) - decays faster
            // GOAL has lower decay rate (0.01) - decays slower
            assertTrue(MemoryType.EPISODE.getDecayRate() > MemoryType.GOAL.getDecayRate());
            assertTrue(MemoryType.FACT.getDecayRate() > MemoryType.PREFERENCE.getDecayRate());
        }
    }

    /**
     * Tests for SearchFilter functionality.
     */
    @Nested
    @DisplayName("Search Filter Tests")
    class SearchFilterTests {

        @BeforeEach
        void setUpFilterTests() {
            // Add various memories for filter testing
            store.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User wants to learn Rust")
                .type(MemoryType.GOAL)
                .importance(0.9)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User prefers vim editor")
                .type(MemoryType.PREFERENCE)
                .importance(0.7)
                .build(), randomEmbedding());

            store.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("User is from Beijing")
                .type(MemoryType.FACT)
                .importance(0.5)
                .build(), randomEmbedding());
        }

        @Test
        @DisplayName("Should filter by multiple memory types")
        void testMultipleTypeFilter() {
            SearchFilter filter = SearchFilter.builder()
                .types(MemoryType.GOAL, MemoryType.PREFERENCE)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(r ->
                r.getType() == MemoryType.GOAL || r.getType() == MemoryType.PREFERENCE));
        }

        @Test
        @DisplayName("Should filter by minimum importance")
        void testMinImportanceFilter() {
            SearchFilter filter = SearchFilter.builder()
                .minImportance(0.8)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
            assertEquals(1, results.size());
            assertEquals(MemoryType.GOAL, results.getFirst().getType());
        }

        @Test
        @DisplayName("Should combine multiple filter criteria")
        void testCombinedFilters() {
            SearchFilter filter = SearchFilter.builder()
                .types(MemoryType.GOAL, MemoryType.PREFERENCE, MemoryType.FACT)
                .minImportance(0.6)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(r -> r.getImportance() >= 0.6));
        }

        @Test
        @DisplayName("Should return empty list when no matches")
        void testNoMatchFilter() {
            SearchFilter filter = SearchFilter.builder()
                .types(MemoryType.RELATIONSHIP)
                .build();

            List<MemoryRecord> results = store.searchByVector(USER_SCOPE, randomEmbedding(), 10, filter);
            assertTrue(results.isEmpty());
        }
    }

    /**
     * Tests for InMemoryStore implementation.
     */
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
                .type(MemoryType.FACT)
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
                .type(MemoryType.FACT)
                .build();

            memStore.save(record, randomEmbedding());
            assertEquals(1, memStore.count(USER_SCOPE));

            memStore.delete(record.getId());
            assertEquals(0, memStore.count(USER_SCOPE));
        }

        @Test
        @DisplayName("Should count by type")
        void testCountByType() {
            MemoryStore memStore = new InMemoryStore();

            memStore.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("Fact 1")
                .type(MemoryType.FACT)
                .build(), randomEmbedding());

            memStore.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("Fact 2")
                .type(MemoryType.FACT)
                .build(), randomEmbedding());

            memStore.save(MemoryRecord.builder()
                .scope(USER_SCOPE)
                .content("Preference 1")
                .type(MemoryType.PREFERENCE)
                .build(), randomEmbedding());

            assertEquals(2, memStore.countByType(USER_SCOPE, MemoryType.FACT));
            assertEquals(1, memStore.countByType(USER_SCOPE, MemoryType.PREFERENCE));
        }
    }
}
