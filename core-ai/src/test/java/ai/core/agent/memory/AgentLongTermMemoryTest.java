package ai.core.agent.memory;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryStore;
import ai.core.memory.longterm.MemoryType;
import ai.core.tool.tools.MemoryRecallTool;
import core.framework.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Agent with LongTermMemory (Unified Memory).
 *
 * <p>Tests the following scenarios:
 * <ul>
 *   <li>Agent auto-registers search_memory_tool</li>
 *   <li>Memory recall with ExecutionContext (userId, sessionId)</li>
 *   <li>Cross-session memory persistence</li>
 *   <li>Conflict resolution during memory extraction</li>
 * </ul>
 *
 * @author xander
 */
//@Disabled
@DisplayName("Agent + LongTermMemory Integration Tests")
class AgentLongTermMemoryTest extends IntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLongTermMemoryTest.class);
    private static final String USER_ID = "test-user-123";
    private static final String SESSION_ID = "session-456";

    @Inject
    LLMProviders llmProviders;

    private LongTermMemory longTermMemory;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        // Using InMemoryStore for testing - developers can implement their own MemoryStore for production
        store = new InMemoryStore();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProviders.getProvider())
            .store(store)
            .config(LongTermMemoryConfig.builder()
                .asyncExtraction(false)
                .enableConflictResolution(true)
                .conflictStrategy(ConflictStrategy.LLM_MERGE)
                .build())
            .build();

        // Clean up any leftover data from previous test runs
        cleanupTestData();
    }

    private void cleanupTestData() {
        store.deleteByScope(MemoryScope.forUser(USER_ID));
        store.deleteByScope(MemoryScope.forUser("multi-turn-user"));
        store.deleteByScope(MemoryScope.forUser("cross-session-user"));
        store.deleteByScope(MemoryScope.forUser("stats-test-user"));
    }

    @Test
    @DisplayName("Agent auto-registers search_memory_tool when unifiedMemory is configured")
    void testAgentAutoRegistersMemoryTool() {
        Agent agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant with memory capabilities.")
            .unifiedMemory(longTermMemory)
            .build();

        // Verify search_memory_tool is registered
        var tools = agent.getToolCalls();
        boolean hasMemoryTool = tools.stream()
            .anyMatch(t -> MemoryRecallTool.TOOL_NAME.equals(t.getName()));

        assertTrue(hasMemoryTool, "Agent should have search_memory_tool registered");
        LOGGER.info("Agent has {} tools registered, including search_memory_tool", tools.size());
    }

    @Test
    @DisplayName("Agent can recall pre-stored memories with ExecutionContext")
    void testAgentRecallsPreStoredMemories() {
        // Pre-store some memories for the user
        MemoryScope scope = MemoryScope.forUser(USER_ID);
        longTermMemory.startSession(scope, SESSION_ID);

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User prefers dark mode in applications")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User is a Java developer working on AI projects")
            .type(MemoryType.FACT)
            .importance(0.85)
            .build(), generateMockEmbedding());

        longTermMemory.endSession();

        // Create agent with unified memory
        Agent agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with memory capabilities.
                When the user asks about their preferences or information,
                use the search_memory_tool to recall relevant memories.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        // Create execution context with userId
        ExecutionContext context = ExecutionContext.builder()
            .userId(USER_ID)
            .sessionId(SESSION_ID)
            .build();

        // Ask about preferences - agent should use search_memory_tool
        String response = agent.run("What are my preferences?", context);

        assertNotNull(response);
        LOGGER.info("Agent response: {}", response);

        // The response should mention dark mode (from pre-stored memory)
        // Note: This depends on LLM actually calling the tool
    }

    @Test
    @DisplayName("Memory is isolated by userId scope")
    void testMemoryIsolationByUser() {
        String user1 = "user-alice";
        String user2 = "user-bob";

        // Store memory for user1
        MemoryScope scope1 = MemoryScope.forUser(user1);
        longTermMemory.startSession(scope1, "session-1");
        store.save(MemoryRecord.builder()
            .scope(scope1)
            .content("Alice likes Python programming")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        // Store memory for user2
        MemoryScope scope2 = MemoryScope.forUser(user2);
        longTermMemory.startSession(scope2, "session-2");
        store.save(MemoryRecord.builder()
            .scope(scope2)
            .content("Bob prefers JavaScript development")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        // Recall for user1 should only get Alice's memory
        var aliceMemories = longTermMemory.recall(scope1, "programming preferences", 10);
        var bobMemories = longTermMemory.recall(scope2, "programming preferences", 10);

        LOGGER.info("Alice's memories: {}", aliceMemories.size());
        LOGGER.info("Bob's memories: {}", bobMemories.size());

        // Verify isolation
        boolean aliceHasPython = aliceMemories.stream()
            .anyMatch(m -> m.getContent().contains("Python"));
        boolean bobHasJavaScript = bobMemories.stream()
            .anyMatch(m -> m.getContent().contains("JavaScript"));

        assertTrue(aliceHasPython, "Alice should have Python memory");
        assertTrue(bobHasJavaScript, "Bob should have JavaScript memory");
    }

    @Test
    @DisplayName("Cross-session memory persistence")
    void testCrossSessionMemoryPersistence() {
        MemoryScope scope = MemoryScope.forUser(USER_ID);

        // Session 1: Store some memories
        longTermMemory.startSession(scope, "session-1");
        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User mentioned they are learning Kotlin")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        LOGGER.info("Session 1 completed, memory stored");

        // Session 2: Memory should persist and be accessible
        longTermMemory.startSession(scope, "session-2");

        var memories = longTermMemory.recall(scope, "learning programming", 5);
        assertFalse(memories.isEmpty(), "Memory should persist across sessions");
        LOGGER.info("Session 2 can access {} memories from previous sessions", memories.size());

        // Verify the agent can also access the memory
        Agent agent = Agent.builder()
            .name("session2-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant with memory.")
            .unifiedMemory(longTermMemory)
            .build();

        // Agent should have access to search_memory_tool
        assertTrue(agent.getToolCalls().stream()
            .anyMatch(t -> MemoryRecallTool.TOOL_NAME.equals(t.getName())),
            "Agent should have search_memory_tool");

        longTermMemory.endSession();
    }

    @Test
    @DisplayName("UnifiedMemoryConfig controls maxRecallRecords")
    void testUnifiedMemoryConfigMaxRecallRecords() {
        UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
            .maxRecallRecords(3)
            .build();

        Agent agent = Agent.builder()
            .name("config-test-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .unifiedMemory(longTermMemory, config)
            .build();

        // Find the MemoryRecallTool and check its maxRecords
        var memoryTool = agent.getToolCalls().stream()
            .filter(t -> t instanceof MemoryRecallTool)
            .map(t -> (MemoryRecallTool) t)
            .findFirst();

        assertTrue(memoryTool.isPresent(), "Should have MemoryRecallTool");
        assertEquals(3, memoryTool.get().getMaxRecords(), "maxRecords should be 3");
        LOGGER.info("MemoryRecallTool configured with maxRecords={}", memoryTool.get().getMaxRecords());
    }

    @Test
    @DisplayName("Agent works normally without ExecutionContext (no memory recall)")
    void testAgentWithoutExecutionContext() {
        Agent agent = Agent.builder()
            .name("no-context-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .unifiedMemory(longTermMemory)
            .build();

        // Run without ExecutionContext
        String response = agent.run("Hello, how are you?");

        assertNotNull(response);
        LOGGER.info("Agent works without ExecutionContext: {}", response);
    }

    /**
     * Generate a mock embedding for testing.
     * In real scenarios, this would come from the LLM provider.
     */
    private float[] generateMockEmbedding() {
        float[] embedding = new float[1536];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = (float) Math.random() * 2 - 1;
        }
        return embedding;
    }

    // ==================== Multi-turn Conversation Tests ====================

    @Test
    @DisplayName("Multi-turn conversation with memory extraction and recall")
    void testMultiTurnConversationWithMemory() {
        String testUserId = "multi-turn-user";
        MemoryScope scope = MemoryScope.forUser(testUserId);

        // Create agent with memory capabilities
        Agent agent = Agent.builder()
            .name("memory-chat-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with long-term memory capabilities.
                You can remember information about users across conversations.

                When users share personal information (preferences, facts about themselves, goals),
                remember this information for future reference.

                When users ask about previous conversations or their information,
                use the search_memory_tool to recall relevant memories.

                Be concise in your responses.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("session-turn-1")
            .build();

        // Turn 1: User shares personal information
        LOGGER.info("=== Turn 1: User shares information ===");
        String response1 = agent.run(
            "Hi! My name is Xander. I'm a AI engineer and I love building AI applications. "
                + "I prefer using Java and Python for my projects.",
            context
        );
        LOGGER.info("Agent response 1: {}", response1);
        assertNotNull(response1);

        // Turn 2: More information
        LOGGER.info("=== Turn 2: User shares more information ===");
        String response2 = agent.run(
            "I'm currently working on a chatbot project. My goal is to make it understand context better.",
            context
        );
        LOGGER.info("Agent response 2: {}", response2);
        assertNotNull(response2);

        // Turn 3: Ask agent to recall (triggers search_memory_tool)
        LOGGER.info("=== Turn 3: User asks about their information ===");
        String response3 = agent.run(
            "What do you remember about me? What are my preferences and goals?",
            context
        );
        LOGGER.info("Agent response 3: {}", response3);
        assertNotNull(response3);

        // Verify memories were stored
        var storedMemories = store.findByScope(scope);
        LOGGER.info("Stored {} memories for user", storedMemories.size());
        storedMemories.forEach(m -> LOGGER.info("  - [{}] {}", m.getType(), m.getContent()));
    }

    @Test
    @DisplayName("Cross-session memory recall - Session 1 stores, Session 2 recalls")
    void testCrossSessionMemoryRecallWithRealAgent() {
        String testUserId = "cross-session-user";
        MemoryScope scope = MemoryScope.forUser(testUserId);

        // ========== Session 1: User shares information ==========
        LOGGER.info("========== SESSION 1: Storing memories ==========");

        Agent agent1 = Agent.builder()
            .name("session1-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with memory capabilities.
                Remember important information users share with you.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context1 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("cross-session-1")
            .build();

        String response1 = agent1.run(
            "Hello! I'm Li Ming from Beijing. I work as a data scientist at a tech company. "
                + "I prefer working with Python and TensorFlow. My current project is about NLP.",
            context1
        );
        LOGGER.info("Session 1 response: {}", response1);

        // End session 1 - this should trigger memory extraction
        longTermMemory.endSession();

        // Check what memories were stored
        var memoriesAfterSession1 = store.findByScope(scope);
        LOGGER.info("Memories after Session 1: {}", memoriesAfterSession1.size());
        memoriesAfterSession1.forEach(m -> LOGGER.info("  - [{}] {}", m.getType(), m.getContent()));

        // ========== Session 2: New session, recall previous information ==========
        LOGGER.info("========== SESSION 2: Recalling memories ==========");

        // Create a fresh agent (simulating new session)
        Agent agent2 = Agent.builder()
            .name("session2-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with memory capabilities.
                Use search_memory_tool to recall information about the user when asked.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context2 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("cross-session-2")
            .build();

        // Ask about previous session information
        String response2 = agent2.run(
            "Do you remember who I am and what I work on?",
            context2
        );
        LOGGER.info("Session 2 response: {}", response2);
        assertNotNull(response2);

        // The response should mention Li Ming, data scientist, Python, TensorFlow, or NLP
        // if memory recall is working correctly
    }

    @Test
    @DisplayName("Memory recall tool is triggered by specific queries")
    void testMemoryRecallToolTriggering() {
        String testUserId = "recall-trigger-user";
        MemoryScope scope = MemoryScope.forUser(testUserId);

        // Pre-store some memories
        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User's favorite programming language is Kotlin")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User is building a mobile app for fitness tracking")
            .type(MemoryType.FACT)
            .importance(0.85)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .scope(scope)
            .content("User's goal is to become a full-stack developer")
            .type(MemoryType.GOAL)
            .importance(0.8)
            .build(), generateMockEmbedding());

        LOGGER.info("Pre-stored 3 memories for user");

        // Create agent
        Agent agent = Agent.builder()
            .name("recall-test-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with access to user memory.
                When the user asks about their information, preferences, or past conversations,
                ALWAYS use the search_memory_tool first to recall relevant information.
                Then respond based on what you find.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("recall-test-session")
            .build();

        // Query 1: Ask about preferences
        LOGGER.info("=== Query 1: Asking about preferences ===");
        String response1 = agent.run("What programming language do I prefer?", context);
        LOGGER.info("Response 1: {}", response1);

        // Query 2: Ask about current project
        LOGGER.info("=== Query 2: Asking about current project ===");
        String response2 = agent.run("What am I currently working on?", context);
        LOGGER.info("Response 2: {}", response2);

        // Query 3: Ask about goals
        LOGGER.info("=== Query 3: Asking about goals ===");
        String response3 = agent.run("What are my goals?", context);
        LOGGER.info("Response 3: {}", response3);

        assertNotNull(response1);
        assertNotNull(response2);
        assertNotNull(response3);
    }

    @Test
    @DisplayName("Direct memory store and recall verification")
    void testDirectMemoryStoreAndRecall() {
        String testUserId = "direct-test-user";
        MemoryScope scope = MemoryScope.forUser(testUserId);

        // Store memories directly
        LOGGER.info("=== Storing memories directly ===");

        MemoryRecord pref = MemoryRecord.builder()
            .scope(scope)
            .content("User prefers dark theme in all applications")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build();

        MemoryRecord fact = MemoryRecord.builder()
            .scope(scope)
            .content("User is 30 years old and lives in Shanghai")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build();

        MemoryRecord goal = MemoryRecord.builder()
            .scope(scope)
            .content("User wants to learn machine learning this year")
            .type(MemoryType.GOAL)
            .importance(0.85)
            .build();

        // Save with real embeddings from LLM
        var embeddingResponse = llmProviders.getProvider().embeddings(
            new ai.core.llm.domain.EmbeddingRequest(List.of(
                pref.getContent(), fact.getContent(), goal.getContent()
            ))
        );
        float[] prefEmbedding = embeddingResponse.embeddings.get(0).embedding.toFloatArray();
        float[] factEmbedding = embeddingResponse.embeddings.get(1).embedding.toFloatArray();
        float[] goalEmbedding = embeddingResponse.embeddings.get(2).embedding.toFloatArray();

        store.save(pref, prefEmbedding);
        store.save(fact, factEmbedding);
        store.save(goal, goalEmbedding);

        LOGGER.info("Stored 3 memories with real embeddings");

        // Recall using semantic search
        LOGGER.info("=== Recalling memories ===");

        var themeMemories = longTermMemory.recall(scope, "theme preferences", 5);
        LOGGER.info("Query 'theme preferences' returned {} results:", themeMemories.size());
        themeMemories.forEach(m -> LOGGER.info("  - {}", m.getContent()));

        var locationMemories = longTermMemory.recall(scope, "where does user live", 5);
        LOGGER.info("Query 'where does user live' returned {} results:", locationMemories.size());
        locationMemories.forEach(m -> LOGGER.info("  - {}", m.getContent()));

        var learningMemories = longTermMemory.recall(scope, "learning goals", 5);
        LOGGER.info("Query 'learning goals' returned {} results:", learningMemories.size());
        learningMemories.forEach(m -> LOGGER.info("  - {}", m.getContent()));

        // Verify recalls work
        assertFalse(themeMemories.isEmpty(), "Should recall theme preferences");
        assertFalse(locationMemories.isEmpty(), "Should recall location info");
        assertFalse(learningMemories.isEmpty(), "Should recall learning goals");
    }

    @Test
    @DisplayName("Memory count and type statistics")
    void testMemoryCountAndStatistics() {
        String testUserId = "stats-test-user";
        MemoryScope scope = MemoryScope.forUser(testUserId);

        // Store various types of memories
        store.save(MemoryRecord.builder()
            .scope(scope).content("Pref 1").type(MemoryType.PREFERENCE).importance(0.9)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .scope(scope).content("Pref 2").type(MemoryType.PREFERENCE).importance(0.8)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .scope(scope).content("Fact 1").type(MemoryType.FACT).importance(0.7)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .scope(scope).content("Goal 1").type(MemoryType.GOAL).importance(0.85)
            .build(), generateMockEmbedding());

        // Count total memories
        int totalCount = store.count(scope);
        LOGGER.info("Total memories: {}", totalCount);
        assertEquals(4, totalCount, "Should have 4 total memories");

        // Count by type
        int prefCount = store.countByType(scope, MemoryType.PREFERENCE);
        int factCount = store.countByType(scope, MemoryType.FACT);
        int goalCount = store.countByType(scope, MemoryType.GOAL);

        LOGGER.info("PREFERENCE: {}, FACT: {}, GOAL: {}", prefCount, factCount, goalCount);
        assertEquals(2, prefCount, "Should have 2 preferences");
        assertEquals(1, factCount, "Should have 1 fact");
        assertEquals(1, goalCount, "Should have 1 goal");
    }
}
