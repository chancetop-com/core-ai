package ai.core.memory.longterm;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Best practice examples for using LongTermMemory.
 *
 * <p>This test demonstrates:
 * <ul>
 *   <li>Pattern 1: Direct MemoryStore usage (for custom storage)</li>
 *   <li>Pattern 2: LongTermMemory facade with session lifecycle</li>
 *   <li>Pattern 3: Integration with Agent via UnifiedMemoryLifecycle</li>
 *   <li>Pattern 4: Custom MemoryStore implementation</li>
 *   <li>Pattern 5: MemoryScope usage patterns</li>
 * </ul>
 *
 * @author xander
 */
@DisplayName("LongTermMemory Best Practice Examples")
class LongTermMemoryBestPracticeTest {

    private static final int EMBEDDING_DIM = 128;
    private static final String USER_ID = "user-123";
    private static final String SESSION_ID = "session-abc";

    private LLMProvider llmProvider;

    @BeforeEach
    void setUp() {
        llmProvider = createMockLLMProvider();
    }

    // ==================== Helper Methods (must be before nested classes) ====================

    private LLMProvider createMockLLMProvider() {
        LLMProvider mock = mock(LLMProvider.class);

        when(mock.completion(any(CompletionRequest.class))).thenAnswer(inv -> {
            Choice choice = new Choice();
            choice.message = Message.of(RoleType.ASSISTANT, "Mock response");
            return CompletionResponse.of(List.of(choice), null);
        });

        AtomicInteger counter = new AtomicInteger(0);
        when(mock.embeddings(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            EmbeddingRequest req = inv.getArgument(0);
            List<EmbeddingResponse.EmbeddingData> dataList = new ArrayList<>();
            for (String text : req.query()) {
                float[] emb = new float[EMBEDDING_DIM];
                int seed = counter.incrementAndGet();
                Random r = new Random(seed);
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    emb[j] = r.nextFloat() * 2 - 1;
                }
                dataList.add(EmbeddingResponse.EmbeddingData.of(text, Embedding.of(emb)));
            }
            return EmbeddingResponse.of(dataList, null);
        });

        return mock;
    }

    private MemoryExtractor createMockExtractor() {
        MemoryExtractor mock = mock(MemoryExtractor.class);
        when(mock.extract(any(MemoryScope.class), any())).thenReturn(List.of(
            MemoryRecord.builder()
                .scope(MemoryScope.forUser(USER_ID))
                .content("User prefers Python programming")
                .importance(0.8)
                .build()
        ));
        return mock;
    }

    private List<Double> generateEmbedding(String text) {
        List<Double> emb = new ArrayList<>();
        Random r = new Random(text.hashCode());
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            emb.add((double) (r.nextFloat() * 2 - 1));
        }
        return emb;
    }

    // ==================== Pattern 1: Direct MemoryStore Usage ====================

    @Nested
    @DisplayName("Pattern 1: Direct MemoryStore Usage")
    class DirectMemoryStoreUsage {

        /**
         * Best practice for directly storing memories without extraction.
         * Use this when you have structured data to store.
         */
        @Test
        @DisplayName("Store memories directly with embeddings")
        void storeMemoriesDirectly() {
            // 1. Create store (use InMemoryStore for testing, implement custom for production)
            MemoryStore store = new InMemoryStore();
            MemoryScope scope = MemoryScope.forUser(USER_ID);

            // 2. Create memory records
            MemoryRecord preference = MemoryRecord.builder()
                .scope(scope)
                .content("User prefers dark mode")
                .importance(0.9)
                .metadata("source", "settings-page")
                .build();

            MemoryRecord fact = MemoryRecord.builder()
                .scope(scope)
                .content("User is a software engineer")
                .importance(0.8)
                .build();

            // 3. Generate embeddings
            List<Double> prefEmbedding = generateEmbedding("User prefers dark mode");
            List<Double> factEmbedding = generateEmbedding("User is a software engineer");

            // 4. Store with embeddings
            store.save(preference, prefEmbedding);
            store.save(fact, factEmbedding);

            // 5. Verify storage
            assertEquals(2, store.count(scope));
        }

        /**
         * Best practice for batch storing memories.
         */
        @Test
        @DisplayName("Batch store multiple memories")
        void batchStoreMemories() {
            MemoryStore store = new InMemoryStore();
            MemoryScope scope = MemoryScope.forUser(USER_ID);

            // Create multiple records
            List<MemoryRecord> records = List.of(
                MemoryRecord.builder()
                    .scope(scope)
                    .content("User likes Python")
                    .importance(0.9)
                    .build(),
                MemoryRecord.builder()
                    .scope(scope)
                    .content("User wants to learn machine learning")
                    .importance(0.85)
                    .build(),
                MemoryRecord.builder()
                    .scope(scope)
                    .content("User works at TechCorp")
                    .importance(0.8)
                    .build()
            );

            // Generate embeddings for all
            var embeddings = records.stream()
                .map(r -> generateEmbedding(r.getContent()))
                .toList();

            // Batch save
            store.saveAll(records, embeddings);

            assertEquals(3, store.count(scope));
        }

        /**
         * Best practice for searching memories.
         */
        @Test
        @DisplayName("Search memories by vector similarity")
        void searchMemoriesByVector() {
            MemoryStore store = new InMemoryStore();
            MemoryScope scope = MemoryScope.forUser(USER_ID);

            // Store some memories
            storeTestMemories(store, scope);

            // Search by vector similarity
            List<Double> queryEmbedding = generateEmbedding("What programming language does user like?");
            List<MemoryRecord> results = store.searchByVector(scope, queryEmbedding, 3);

            assertFalse(results.isEmpty());
            assertTrue(results.size() <= 3);
        }

        /**
         * Best practice for filtering search results by importance.
         */
        @Test
        @DisplayName("Search with importance filter")
        void searchWithFilter() {
            MemoryStore store = new InMemoryStore();
            MemoryScope scope = MemoryScope.forUser(USER_ID);

            storeTestMemories(store, scope);

            // Search with minimum importance filter
            List<Double> queryEmbedding = generateEmbedding("user preferences");
            SearchFilter filter = SearchFilter.builder()
                .minImportance(0.8)
                .build();

            List<MemoryRecord> results = store.searchByVector(scope, queryEmbedding, 5, filter);

            results.forEach(r -> assertTrue(r.getImportance() >= 0.8));
        }

        private void storeTestMemories(MemoryStore store, MemoryScope scope) {
            List<MemoryRecord> records = List.of(
                MemoryRecord.builder().scope(scope).content("User likes Java").importance(0.9).build(),
                MemoryRecord.builder().scope(scope).content("User is learning Kotlin").importance(0.85).build(),
                MemoryRecord.builder().scope(scope).content("User joined team in 2023").importance(0.7).build()
            );
            List<List<Double>> embeddings = records.stream().map(r -> generateEmbedding(r.getContent())).toList();
            store.saveAll(records, embeddings);
        }
    }

    // ==================== Pattern 2: LongTermMemory Facade ====================

    @Nested
    @DisplayName("Pattern 2: LongTermMemory Facade with Session")
    class LongTermMemoryFacadeUsage {

        /**
         * Best practice for using LongTermMemory with session lifecycle.
         * Use this when you want automatic memory extraction from conversations.
         */
        @Test
        @DisplayName("Session-based memory extraction")
        void sessionBasedExtraction() {
            // 1. Build LongTermMemory with custom extractor
            LongTermMemory memory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .store(new InMemoryStore())
                .extractor(createMockExtractor())
                .config(LongTermMemoryConfig.builder()
                    .maxBufferTurns(1)          // Extract every turn for testing
                    .asyncExtraction(false)     // Sync for predictable testing
                    .build())
                .build();

            // 2. Start session
            memory.startSessionForUser(USER_ID, SESSION_ID);

            // 3. Feed conversation messages
            memory.onMessage(Message.of(RoleType.USER, "I prefer working with Python"));
            memory.onMessage(Message.of(RoleType.ASSISTANT, "Got it! I'll remember that."));

            // 4. End session (triggers final extraction)
            memory.endSession();

            // 5. Recall memories in new session
            memory.startSessionForUser(USER_ID, "new-session");
            List<MemoryRecord> recalled = memory.recall("programming language", 5);

            // Note: Results depend on extractor implementation
            assertNotNull(recalled);
        }

        /**
         * Best practice for recalling memories.
         */
        @Test
        @DisplayName("Recall memories by query")
        void recallMemories() {
            LongTermMemory memory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .store(new InMemoryStore())
                .build();

            // Pre-populate memories directly via store
            MemoryScope scope = MemoryScope.forUser(USER_ID);
            MemoryStore store = memory.getStore();

            store.save(
                MemoryRecord.builder()
                    .scope(scope)
                    .content("User prefers concise answers")
                    .importance(0.9)
                    .build(),
                generateEmbedding("User prefers concise answers")
            );

            store.save(
                MemoryRecord.builder()
                    .scope(scope)
                    .content("User wants to become a tech lead")
                    .importance(0.85)
                    .build(),
                generateEmbedding("User wants to become a tech lead")
            );

            // Start session and recall
            memory.startSessionForUser(USER_ID, SESSION_ID);
            List<MemoryRecord> memories = memory.recall("career", 5);

            assertNotNull(memories);
        }
    }

    // ==================== Pattern 3: Agent Integration ====================

    @Nested
    @DisplayName("Pattern 3: Agent Integration")
    class AgentIntegrationUsage {

        /**
         * Best practice for integrating LongTermMemory with Agent.
         * The MemoryRecallTool is auto-registered when autoRecall=true.
         */
        @Test
        @DisplayName("Agent with unified memory (auto recall)")
        void agentWithUnifiedMemoryAutoRecall() {
            // 1. Create LongTermMemory
            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .store(new InMemoryStore())
                .config(LongTermMemoryConfig.builder()
                    .asyncExtraction(false)
                    .build())
                .build();

            // 2. Build agent with unified memory
            Agent agent = Agent.builder()
                .name("memory-agent")
                .llmProvider(llmProvider)
                .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
                    .autoRecall(true)          // Auto-register MemoryRecallTool
                    .maxRecallRecords(5)       // Limit recall results
                    .build())
                .build();

            // 3. Verify MemoryRecallTool is registered
            boolean hasRecallTool = agent.getToolCalls().stream()
                .anyMatch(t -> "search_memory_tool".equals(t.getName()));
            assertTrue(hasRecallTool, "MemoryRecallTool should be auto-registered");

            // 4. Execution context is used when running agent
            ExecutionContext context = ExecutionContext.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .build();
            assertNotNull(context.getUserId());
            // The agent can now use search_memory_tool to recall memories
        }

        /**
         * Best practice for manual memory recall (autoRecall=false).
         * Use this when you want to control when memories are recalled.
         */
        @Test
        @DisplayName("Agent with manual memory control")
        void agentWithManualMemoryControl() {
            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .store(new InMemoryStore())
                .build();

            // Build agent without auto recall
            Agent agent = Agent.builder()
                .name("manual-memory-agent")
                .llmProvider(llmProvider)
                .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
                    .autoRecall(false)  // Don't auto-register tool
                    .build())
                .build();

            // No MemoryRecallTool registered
            boolean hasRecallTool = agent.getToolCalls().stream()
                .anyMatch(t -> "search_memory_tool".equals(t.getName()));
            assertFalse(hasRecallTool, "MemoryRecallTool should not be registered");

            // Manually recall memories before agent run
            longTermMemory.startSessionForUser(USER_ID, SESSION_ID);
            List<MemoryRecord> memories = longTermMemory.recall("user preferences", 3);

            // Format and inject into system prompt or context
            String memoryContext = longTermMemory.formatAsContext(memories);
            assertNotNull(memoryContext);
        }
    }

    // ==================== Pattern 4: Custom MemoryStore ====================

    @Nested
    @DisplayName("Pattern 4: Custom MemoryStore Implementation")
    class CustomMemoryStorePattern {

        /**
         * Example skeleton for a custom MemoryStore implementation.
         * Implement this interface to use your own storage backend.
         */
        @Test
        @DisplayName("Custom store implementation pattern")
        void customStoreImplementation() {
            // Example: Using a custom database-backed store
            MemoryStore customStore = new CustomDatabaseStore();

            LongTermMemory memory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .store(customStore)  // Use your custom store
                .build();

            assertNotNull(memory);
        }

        /**
         * Skeleton implementation showing what methods to implement.
         */
        static class CustomDatabaseStore implements MemoryStore {
            // In production, inject your database client here

            @Override
            public void save(MemoryRecord record) {
                // INSERT INTO memories (id, user_id, content, type, ...) VALUES (...)
            }

            @Override
            public void save(MemoryRecord record, List<Double> embedding) {
                // Store record in metadata table
                // Store embedding in vector index
            }

            @Override
            public void saveAll(List<MemoryRecord> records, List<List<Double>> embeddings) {
                // Batch insert for efficiency
                for (int i = 0; i < records.size(); i++) {
                    save(records.get(i), embeddings.get(i));
                }
            }

            @Override
            public java.util.Optional<MemoryRecord> findById(String id) {
                // SELECT * FROM memories WHERE id = ?
                return java.util.Optional.empty();
            }

            @Override
            public List<MemoryRecord> findByScope(MemoryScope scope) {
                // SELECT * FROM memories WHERE user_id = ? AND session_id = ? ...
                return List.of();
            }

            @Override
            public List<MemoryRecord> searchByVector(MemoryScope scope, List<Double> queryEmbedding, int topK) {
                // Use vector database similarity search
                // e.g., Milvus, Pinecone, pgvector
                return List.of();
            }

            @Override
            public List<MemoryRecord> searchByVector(MemoryScope scope, List<Double> queryEmbedding, int topK,
                                                      SearchFilter filter) {
                // Vector search with metadata filtering
                return List.of();
            }

            @Override
            public List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK) {
                // Full-text search: SELECT * FROM memories WHERE content LIKE ?
                return List.of();
            }

            @Override
            public List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK,
                                                       SearchFilter filter) {
                return List.of();
            }

            @Override
            public void delete(String id) {
                // DELETE FROM memories WHERE id = ?
            }

            @Override
            public void deleteByScope(MemoryScope scope) {
                // DELETE FROM memories WHERE user_id = ?
            }

            @Override
            public void recordAccess(List<String> ids) {
                // UPDATE memories SET access_count = access_count + 1 WHERE id IN (...)
            }

            @Override
            public void updateDecayFactor(String id, double decayFactor) {
                // UPDATE memories SET decay_factor = ? WHERE id = ?
            }

            @Override
            public List<MemoryRecord> findDecayed(MemoryScope scope, double threshold) {
                // SELECT * FROM memories WHERE decay_factor < ?
                return List.of();
            }

            @Override
            public int deleteDecayed(double threshold) {
                // DELETE FROM memories WHERE decay_factor < ?
                return 0;
            }

            @Override
            public int count(MemoryScope scope) {
                // SELECT COUNT(*) FROM memories WHERE user_id = ?
                return 0;
            }
        }
    }

    // ==================== Pattern 5: MemoryScope Best Practices ====================

    @Nested
    @DisplayName("Pattern 5: MemoryScope Usage")
    class MemoryScopePatterns {

        @Test
        @DisplayName("User-level scope (cross-session memories)")
        void userLevelScope() {
            // User-level: memories persist across all sessions
            MemoryScope scope = MemoryScope.forUser("user-123");

            // Good for: preferences, facts, goals
            // These memories are accessible in any session for this user
            assertNotNull(scope.getUserId());
            assertFalse(scope.hasSessionId());
            assertFalse(scope.hasAgentName());
        }

        @Test
        @DisplayName("Session-level scope (session-specific memories)")
        void sessionLevelScope() {
            // Session-level: memories specific to a conversation
            MemoryScope scope = MemoryScope.forSession("user-123", "session-abc");

            // Good for: conversation context, temporary decisions
            // These memories are only accessible within this session
            assertTrue(scope.hasUserId());
            assertTrue(scope.hasSessionId());
            assertFalse(scope.hasAgentName());
        }

        @Test
        @DisplayName("Agent-level scope (agent-specific memories)")
        void agentLevelScope() {
            // Agent-level: memories specific to an agent
            MemoryScope scope = MemoryScope.forAgent("user-123", "support-agent");

            // Good for: agent-specific user preferences
            // e.g., user prefers detailed answers from support agent
            assertTrue(scope.hasUserId());
            assertFalse(scope.hasSessionId());
            assertTrue(scope.hasAgentName());
        }

        @Test
        @DisplayName("Full scope (most specific)")
        void fullScope() {
            // Full scope: most specific isolation
            MemoryScope scope = MemoryScope.of("user-123", "session-abc", "support-agent");

            // Good for: highly specific context that shouldn't leak
            assertTrue(scope.hasUserId());
            assertTrue(scope.hasSessionId());
            assertTrue(scope.hasAgentName());
            assertEquals("u:user-123/s:session-abc/a:support-agent", scope.toKey());
        }
    }
}
