package ai.core.agent.memory;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.MemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.memory.history.ChatHistoryStore;
import ai.core.memory.history.ChatSession;
import ai.core.memory.history.JdbcChatHistoryStore;
import ai.core.tool.tools.MemoryRecallTool;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
    private static final String PG_HOST = "localhost";
    private static final int PG_PORT = 5432;
    private static final String PG_DATABASE = "postgres";
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "postgres";

    @Inject
    LLMProviders llmProviders;

    private LongTermMemory longTermMemory;
    private MemoryStore store;
    private ChatHistoryStore chatHistoryStore;

    @BeforeEach
    void setUp() {
        HikariDataSource dataSource = createPostgresDataSource();
        // Using InMemoryStore for testing - developers can implement their own MemoryStore for production
        store = new InMemoryStore();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProviders.getProvider())
            .store(store)
            .config(LongTermMemoryConfig.builder()
                .asyncExtraction(false)
                .enableConflictResolution(true)
                .conflictStrategy(ConflictStrategy.NEWEST_WITH_MERGE)
                .build())
            .build();

        // Initialize chat history store
        var jdbcHistoryStore = new JdbcChatHistoryStore(dataSource, JdbcChatHistoryStore.DatabaseType.POSTGRESQL);
        jdbcHistoryStore.initialize();
        chatHistoryStore = jdbcHistoryStore;

        // Clean up any leftover data from previous test runs
        cleanupTestData();
    }

    private void cleanupTestData() {
        store.deleteByNamespace(Namespace.forUser(USER_ID));
        store.deleteByNamespace(Namespace.forUser("multi-turn-user"));
        store.deleteByNamespace(Namespace.forUser("cross-session-user"));
        store.deleteByNamespace(Namespace.forUser("history-test-user"));
        store.deleteByNamespace(Namespace.forUser("stats-test-user"));
        chatHistoryStore.deleteByUser(USER_ID);
        chatHistoryStore.deleteByUser("multi-turn-user");
        chatHistoryStore.deleteByUser("cross-session-user");
        chatHistoryStore.deleteByUser("history-test-user");
    }
    //todo abstract data layer
    private HikariDataSource createPostgresDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", PG_HOST, PG_PORT, PG_DATABASE));
        config.setUsername(PG_USER);
        config.setPassword(PG_PASSWORD);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
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
        Namespace namespace = Namespace.forUser(USER_ID);
        longTermMemory.startSession(namespace, SESSION_ID);

        store.save(MemoryRecord.builder()
            .namespace(namespace)
            .content("User prefers dark mode in applications")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .namespace(namespace)
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
    @DisplayName("Memory is isolated by userId namespace")
    void testMemoryIsolationByUser() {
        String user1 = "user-alice";
        String user2 = "user-bob";

        // Store memory for user1
        Namespace ns1 = Namespace.forUser(user1);
        longTermMemory.startSession(ns1, "session-1");
        store.save(MemoryRecord.builder()
            .namespace(ns1)
            .content("Alice likes Python programming")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        // Store memory for user2
        Namespace ns2 = Namespace.forUser(user2);
        longTermMemory.startSession(ns2, "session-2");
        store.save(MemoryRecord.builder()
            .namespace(ns2)
            .content("Bob prefers JavaScript development")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        // Recall for user1 should only get Alice's memory
        var aliceMemories = longTermMemory.recall(ns1, "programming preferences", 10);
        var bobMemories = longTermMemory.recall(ns2, "programming preferences", 10);

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
        Namespace namespace = Namespace.forUser(USER_ID);

        // Session 1: Store some memories
        longTermMemory.startSession(namespace, "session-1");
        store.save(MemoryRecord.builder()
            .namespace(namespace)
            .content("User mentioned they are learning Kotlin")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        LOGGER.info("Session 1 completed, memory stored");

        // Session 2: Memory should persist and be accessible
        longTermMemory.startSession(namespace, "session-2");

        var memories = longTermMemory.recall(namespace, "learning programming", 5);
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
            .conflictStrategy(ConflictStrategy.IMPORTANCE_BASED)
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
        Namespace namespace = Namespace.forUser(testUserId);

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
        var storedMemories = store.findByNamespace(namespace);
        LOGGER.info("Stored {} memories for user", storedMemories.size());
        storedMemories.forEach(m -> LOGGER.info("  - [{}] {}", m.getType(), m.getContent()));
    }

    @Test
    @DisplayName("Cross-session memory recall - Session 1 stores, Session 2 recalls")
    void testCrossSessionMemoryRecallWithRealAgent() {
        String testUserId = "cross-session-user";
        Namespace namespace = Namespace.forUser(testUserId);

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
        var memoriesAfterSession1 = store.findByNamespace(namespace);
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
        Namespace namespace = Namespace.forUser(testUserId);

        // Pre-store some memories
        store.save(MemoryRecord.builder()
            .namespace(namespace)
            .content("User's favorite programming language is Kotlin")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .namespace(namespace)
            .content("User is building a mobile app for fitness tracking")
            .type(MemoryType.FACT)
            .importance(0.85)
            .build(), generateMockEmbedding());

        store.save(MemoryRecord.builder()
            .namespace(namespace)
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
        Namespace namespace = Namespace.forUser(testUserId);

        // Store memories directly
        LOGGER.info("=== Storing memories directly ===");

        MemoryRecord pref = MemoryRecord.builder()
            .namespace(namespace)
            .content("User prefers dark theme in all applications")
            .type(MemoryType.PREFERENCE)
            .importance(0.9)
            .build();

        MemoryRecord fact = MemoryRecord.builder()
            .namespace(namespace)
            .content("User is 30 years old and lives in Shanghai")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build();

        MemoryRecord goal = MemoryRecord.builder()
            .namespace(namespace)
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

        var themeMemories = longTermMemory.recall(namespace, "theme preferences", 5);
        LOGGER.info("Query 'theme preferences' returned {} results:", themeMemories.size());
        themeMemories.forEach(m -> LOGGER.info("  - {}", m.getContent()));

        var locationMemories = longTermMemory.recall(namespace, "where does user live", 5);
        LOGGER.info("Query 'where does user live' returned {} results:", locationMemories.size());
        locationMemories.forEach(m -> LOGGER.info("  - {}", m.getContent()));

        var learningMemories = longTermMemory.recall(namespace, "learning goals", 5);
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
        Namespace namespace = Namespace.forUser(testUserId);

        // Store various types of memories
        store.save(MemoryRecord.builder()
            .namespace(namespace).content("Pref 1").type(MemoryType.PREFERENCE).importance(0.9)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .namespace(namespace).content("Pref 2").type(MemoryType.PREFERENCE).importance(0.8)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .namespace(namespace).content("Fact 1").type(MemoryType.FACT).importance(0.7)
            .build(), generateMockEmbedding());
        store.save(MemoryRecord.builder()
            .namespace(namespace).content("Goal 1").type(MemoryType.GOAL).importance(0.85)
            .build(), generateMockEmbedding());

        // Count total memories
        int totalCount = store.count(namespace);
        LOGGER.info("Total memories: {}", totalCount);
        assertEquals(4, totalCount, "Should have 4 total memories");

        // Count by type
        int prefCount = store.countByType(namespace, MemoryType.PREFERENCE);
        int factCount = store.countByType(namespace, MemoryType.FACT);
        int goalCount = store.countByType(namespace, MemoryType.GOAL);

        LOGGER.info("PREFERENCE: {}, FACT: {}, GOAL: {}", prefCount, factCount, goalCount);
        assertEquals(2, prefCount, "Should have 2 preferences");
        assertEquals(1, factCount, "Should have 1 fact");
        assertEquals(1, goalCount, "Should have 1 goal");
    }

    // ==================== Chat History Tests ====================

    @Test
    @DisplayName("Agent with ChatHistory - conversation is persisted")
    void testAgentWithChatHistoryPersistence() {
        String testUserId = "history-test-user";
        String sessionId = "history-session-1";

        // Create agent with both memory and chat history
        Agent agent = Agent.builder()
            .name("history-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant. Be concise.")
            .unifiedMemory(longTermMemory)
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext context = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId(sessionId)
            .build();

        // Have a conversation
        LOGGER.info("=== Starting conversation with chat history ===");
        String response1 = agent.run("Hello! My name is Test User.", context);
        LOGGER.info("Response 1: {}", response1);
        assertNotNull(response1);

        String response2 = agent.run("What's 2 + 2?", context);
        LOGGER.info("Response 2: {}", response2);
        assertNotNull(response2);

        // Verify chat history was persisted
        var savedSession = chatHistoryStore.findById(sessionId);
        assertTrue(savedSession.isPresent(), "Session should be persisted");

        ChatSession session = savedSession.get();
        LOGGER.info("Persisted session: id={}, title={}, messageCount={}",
            session.getId(), session.getTitle(), session.getMessageCount());

        // Verify messages
        var messages = chatHistoryStore.getMessages(sessionId);
        LOGGER.info("Persisted {} messages:", messages.size());
        messages.forEach(m -> LOGGER.info("  - [{}] {}", m.role, truncate(m.content, 50)));

        assertTrue(messages.size() >= 2, "Should have at least 2 messages");
    }

    @Test
    @DisplayName("Chat history with multiple sessions for same user")
    void testChatHistoryMultipleSessions() {
        String testUserId = "history-test-user";

        // Session 1
        Agent agent1 = Agent.builder()
            .name("session1-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext ctx1 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("multi-session-1")
            .build();

        agent1.run("Hello from session 1!", ctx1);

        // Session 2
        Agent agent2 = Agent.builder()
            .name("session2-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext ctx2 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId("multi-session-2")
            .build();

        agent2.run("Hello from session 2!", ctx2);

        // Verify both sessions exist
        var sessions = chatHistoryStore.listByUser(testUserId);
        LOGGER.info("User has {} sessions", sessions.size());
        sessions.forEach(s -> LOGGER.info("  - Session: {} - {}", s.getId(), s.getTitle()));

        assertTrue(sessions.size() >= 2, "Should have at least 2 sessions");

        // Verify session count
        int count = chatHistoryStore.countByUser(testUserId);
        assertEquals(sessions.size(), count, "Count should match list size");
    }

    @Test
    @DisplayName("Chat history combined with LongTermMemory - full memory stack")
    void testFullMemoryStack() {
        String testUserId = "history-test-user";
        String sessionId = "full-stack-session";
        Namespace namespace = Namespace.forUser(testUserId);

        // Create agent with full memory stack
        Agent agent = Agent.builder()
            .name("full-stack-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with memory capabilities.
                You can remember information about users.
                Use search_memory_tool when asked about user's information.
                """)
            .unifiedMemory(longTermMemory)
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext context = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId(sessionId)
            .build();

        // Conversation
        LOGGER.info("=== Full Memory Stack Test ===");
        String response1 = agent.run(
            "Hi! I'm a software engineer. I love Java and Spring Boot.",
            context
        );
        LOGGER.info("Response 1: {}", response1);

        String response2 = agent.run("What do you remember about me?", context);
        LOGGER.info("Response 2: {}", response2);

        // Verify chat history
        var chatSession = chatHistoryStore.findById(sessionId);
        assertTrue(chatSession.isPresent(), "Chat session should exist");
        LOGGER.info("Chat history: {} messages", chatSession.get().getMessageCount());

        // Verify long-term memory extraction (may take time for async)
        var memories = store.findByNamespace(namespace);
        LOGGER.info("Long-term memories: {} records", memories.size());
        memories.forEach(m -> LOGGER.info("  - [{}] {}", m.getType(), m.getContent()));

        // Both should work together
        assertNotNull(response1);
        assertNotNull(response2);
    }

    @Test
    @DisplayName("Reload and continue conversation from chat history")
    void testReloadConversationFromHistory() {
        String testUserId = "history-test-user";
        String sessionId = "reload-session";

        // Session 1: Start conversation
        Agent agent1 = Agent.builder()
            .name("reload-agent-1")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext ctx1 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId(sessionId)
            .build();

        agent1.run("Remember this: the secret code is ABC123", ctx1);
        LOGGER.info("Session 1: Stored secret code");

        // Verify session was saved
        var savedAfter1 = chatHistoryStore.findById(sessionId);
        assertTrue(savedAfter1.isPresent());
        int messageCountAfter1 = savedAfter1.get().getMessageCount();
        LOGGER.info("After session 1: {} messages", messageCountAfter1);

        // Session 2: Continue conversation (simulate app restart)
        Agent agent2 = Agent.builder()
            .name("reload-agent-2")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .chatHistory(chatHistoryStore)
            .build();

        ExecutionContext ctx2 = ExecutionContext.builder()
            .userId(testUserId)
            .sessionId(sessionId)  // Same session ID
            .build();

        agent2.run("What was the secret code?", ctx2);
        LOGGER.info("Session 2: Asked about secret code");

        // Verify messages were appended
        var savedAfter2 = chatHistoryStore.findById(sessionId);
        assertTrue(savedAfter2.isPresent());
        int messageCountAfter2 = savedAfter2.get().getMessageCount();
        LOGGER.info("After session 2: {} messages", messageCountAfter2);

        assertTrue(messageCountAfter2 > messageCountAfter1,
            "Message count should increase after second session");

        // List all messages
        var allMessages = chatHistoryStore.getMessages(sessionId);
        LOGGER.info("All {} messages in session:", allMessages.size());
        allMessages.forEach(m -> LOGGER.info("  - [{}] {}",
            m.role, truncate(m.content, 60)));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
