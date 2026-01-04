package ai.core.memory;

import ai.core.agent.Agent;
import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryStore;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Test demonstrating Agent with both short-term and long-term memory.
 *
 * <p>Short-term memory: Summarizes conversation history within a session
 * <p>Long-term memory: Persists user preferences, facts, and behaviors across sessions
 *
 * @author xander
 */
@DisplayName("Agent with Memory Integration Tests")
class AgentWithMemoryTest {

    private static final int EMBEDDING_DIM = 128;
    private static final String USER_ID = "user-123";
    private static final String SESSION_ID = "session-456";

    private LLMProvider llmProvider;
    private LongTermMemory longTermMemory;
    private MemoryStore store;
    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
        llmProvider = createMockLLMProvider();

        // Create in-memory store for testing
        store = new InMemoryStore();

        // Build long-term memory
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProvider)
            .store(store)
            .extractor(createMockExtractor())
            .config(LongTermMemoryConfig.builder()
                .asyncExtraction(false)
                .build())
            .build();
    }

    @Test
    @DisplayName("Agent with short-term memory configuration")
    void testAgentWithShortTermMemory() {
        // Create agent with short-term memory enabled (default)
        Agent agent = Agent.builder()
            .name("assistant")
            .systemPrompt("You are a helpful assistant.")
            .llmProvider(llmProvider)
            .enableShortTermMemory(true)
            .slidingWindowTurns(10)
            .build();

        assertNotNull(agent);
        // Short-term memory is enabled by default when enableShortTermMemory(true)
        assertNotNull(agent.getMessages());
    }

    @Test
    @DisplayName("Long-term memory persists user preferences across sessions")
    void testLongTermMemoryPersistence() {
        MemoryScope userScope = MemoryScope.forUser(USER_ID);

        // Session 1: User shares preferences
        longTermMemory.startSessionForUser(USER_ID, SESSION_ID);

        // Manually save some preferences (simulating extraction)
        MemoryRecord preference1 = MemoryRecord.builder()
            .scope(userScope)
            .content("User prefers dark mode for all applications")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build();

        MemoryRecord preference2 = MemoryRecord.builder()
            .scope(userScope)
            .content("User's favorite programming language is Python")
            .type(MemoryType.PREFERENCE)
            .importance(0.8)
            .build();

        store.save(preference1, randomEmbedding());
        store.save(preference2, randomEmbedding());

        longTermMemory.endSession();

        // Session 2: Recall memories
        longTermMemory.startSessionForUser(USER_ID, "session-789");

        assertTrue(longTermMemory.hasMemories());
        assertEquals(2, longTermMemory.getMemoryCount());

        // Recall preferences
        var recalled = longTermMemory.recall("user preferences", 5);
        assertFalse(recalled.isEmpty());

        longTermMemory.endSession();
    }

    @Test
    @DisplayName("Agent integrates long-term memory context into conversation")
    void testAgentWithLongTermMemoryContext() {
        MemoryScope userScope = MemoryScope.forUser(USER_ID);

        // Pre-populate long-term memory with user facts
        MemoryRecord fact = MemoryRecord.builder()
            .scope(userScope)
            .content("User is a software engineer working on AI projects")
            .type(MemoryType.FACT)
            .importance(0.85)
            .build();

        store.save(fact, randomEmbedding());

        // Start session
        longTermMemory.startSessionForUser(USER_ID, SESSION_ID);

        // Recall relevant context for the conversation
        var relevantMemories = longTermMemory.recall("work and profession", 3);
        String memoryContext = longTermMemory.formatAsContext(relevantMemories);

        // Build system prompt with memory context
        String systemPromptWithMemory = """
            You are a helpful assistant.

            %s

            Use the above information about the user to personalize your responses.
            """.formatted(memoryContext);

        // Create agent with personalized context
        Agent agent = Agent.builder()
            .name("personalized-assistant")
            .systemPrompt(systemPromptWithMemory)
            .llmProvider(llmProvider)
            .enableShortTermMemory(true)
            .build();

        assertNotNull(agent);
        assertTrue(systemPromptWithMemory.contains("[User Memory]"));

        longTermMemory.endSession();
    }

    @Test
    @DisplayName("Combined short-term and long-term memory workflow")
    void testCombinedMemoryWorkflow() {
        MemoryScope userScope = MemoryScope.forUser(USER_ID);

        // Pre-populate some long-term memories
        store.save(MemoryRecord.builder()
            .scope(userScope)
            .content("User prefers concise explanations")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(userScope)
            .content("User is learning machine learning")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build(), randomEmbedding());

        // Start new session
        longTermMemory.startSessionForUser(USER_ID, SESSION_ID);

        // Recall long-term memories for context
        var memories = longTermMemory.recall("user background", 5);
        String longTermContext = longTermMemory.formatAsContext(memories);

        // Create agent with both memory types
        Agent agent = Agent.builder()
            .name("smart-assistant")
            .systemPrompt("""
                You are a helpful assistant with memory capabilities.

                Long-term user information:
                %s

                Use conversation history (short-term) and user background (long-term)
                to provide personalized, relevant responses.
                """.formatted(longTermContext))
            .llmProvider(llmProvider)
            .enableShortTermMemory(true)           // Enable short-term memory
            .slidingWindowTurns(20)       // Keep last 20 turns
            .build();

        assertNotNull(agent);

        // Track new information for long-term storage
        longTermMemory.onMessage(Message.of(RoleType.USER, "I find backpropagation confusing"));

        // End session - would trigger memory extraction in real scenario
        longTermMemory.endSession();
    }

    @Test
    @DisplayName("Memory-aware agent with custom MemoryStore implementation")
    void testMemoryAwareAgentWithCustomStore() {
        // Developers can implement their own MemoryStore for persistence
        // Here we demonstrate with InMemoryStore
        MemoryStore customStore = new InMemoryStore();

        // Build long-term memory with custom store
        LongTermMemory persistentMemory = LongTermMemory.builder()
            .llmProvider(llmProvider)
            .store(customStore)
            .extractor(createMockExtractor())
            .build();

        MemoryScope userScope = MemoryScope.forUser("persistent-user");

        // Session 1: Save some memories
        persistentMemory.startSession(userScope, "session-1");
        customStore.save(MemoryRecord.builder()
            .scope(userScope)
            .content("User is a Python developer")
            .type(MemoryType.FACT)
            .build(), randomEmbedding());
        persistentMemory.endSession();

        // Session 2: Memories should persist (within same JVM)
        persistentMemory.startSession(userScope, "session-2");
        assertEquals(1, persistentMemory.getMemoryCount());
        persistentMemory.endSession();
    }

    @Test
    @DisplayName("Format memories as context for agent prompt")
    void testFormatMemoriesAsContext() {
        MemoryScope scope = MemoryScope.forUser("format-test-user");

        // Add various memory types
        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User prefers TypeScript over JavaScript")
            .type(MemoryType.PREFERENCE)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User works at a startup company")
            .type(MemoryType.FACT)
            .build(), randomEmbedding());

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User often asks about React best practices")
            .type(MemoryType.EPISODE)
            .build(), randomEmbedding());

        longTermMemory.startSession(scope, "test-session");

        // Format all memories as context
        var allMemories = longTermMemory.recall("user information", 10);
        String context = longTermMemory.formatAsContext(allMemories);

        assertNotNull(context);
        assertTrue(context.contains("[User Memory]"));

        longTermMemory.endSession();
    }

    // ==================== Helper Methods ====================

    private float[] randomEmbedding() {
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding[i] = random.nextFloat() * 2 - 1;
        }
        return embedding;
    }

    private LLMProvider createMockLLMProvider() {
        LLMProvider mock = mock(LLMProvider.class);

        // Mock completion
        when(mock.completion(any(CompletionRequest.class))).thenAnswer(inv -> {
            Choice choice = new Choice();
            choice.message = Message.of(RoleType.ASSISTANT, "Mock response");
            return CompletionResponse.of(List.of(choice), null);
        });

        // Mock embeddings
        AtomicInteger embeddingCounter = new AtomicInteger(0);
        when(mock.embeddings(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            EmbeddingRequest req = inv.getArgument(0);
            List<EmbeddingResponse.EmbeddingData> dataList = new ArrayList<>();

            for (int i = 0; i < req.query().size(); i++) {
                // Generate deterministic but unique embeddings
                float[] emb = new float[EMBEDDING_DIM];
                int seed = embeddingCounter.incrementAndGet();
                Random r = new Random(seed);
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    emb[j] = r.nextFloat() * 2 - 1;
                }
                dataList.add(EmbeddingResponse.EmbeddingData.of(req.query().get(i), Embedding.of(emb)));
            }
            return EmbeddingResponse.of(dataList, null);
        });

        return mock;
    }

    private MemoryExtractor createMockExtractor() {
        MemoryExtractor mock = mock(MemoryExtractor.class);
        when(mock.extract(any(MemoryScope.class), any())).thenReturn(List.of());
        return mock;
    }
}
