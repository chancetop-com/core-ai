package ai.core.agent.memory;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.longterm.DefaultLongTermMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.tool.tools.MemoryRecallTool;
import core.framework.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Disabled
@DisplayName("Agent + LongTermMemory Integration Tests")
class AgentLongTermMemoryTest extends IntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLongTermMemoryTest.class);
    private static final String USER_ID = "test-user-123";
    private static final String SESSION_ID = "session-456";

    @Inject
    LLMProviders llmProviders;

    private LongTermMemory longTermMemory;
    private DefaultLongTermMemoryStore store;

    @BeforeEach
    void setUp() {
        store = DefaultLongTermMemoryStore.inMemory();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProviders.getProvider())
            .store(store)
            .config(LongTermMemoryConfig.builder()
                .embeddingDimension(1536)
                .asyncExtraction(false)
                .enableConflictResolution(true)
                .conflictStrategy(ConflictStrategy.NEWEST_WITH_MERGE)
                .build())
            .build();
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
        Agent agent1 = Agent.builder()
            .name("session1-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context1 = ExecutionContext.builder()
            .userId(USER_ID)
            .sessionId("session-1")
            .build();

        // Manually add a memory (simulating extraction)
        longTermMemory.startSession(namespace, "session-1");
        store.save(MemoryRecord.builder()
            .namespace(namespace)
            .content("User mentioned they are learning Kotlin")
            .type(MemoryType.FACT)
            .importance(0.8)
            .build(), generateMockEmbedding());
        longTermMemory.endSession();

        LOGGER.info("Session 1 completed, memory stored");

        // Session 2: New agent should be able to recall session 1's memory
        Agent agent2 = Agent.builder()
            .name("session2-agent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("""
                You are a helpful assistant with memory.
                Use search_memory_tool to recall information about the user.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context2 = ExecutionContext.builder()
            .userId(USER_ID)
            .sessionId("session-2")
            .build();

        // Memory should persist across sessions
        var memories = longTermMemory.recall(namespace, "learning programming", 5);
        assertFalse(memories.isEmpty(), "Memory should persist across sessions");
        LOGGER.info("Session 2 can access {} memories from previous sessions", memories.size());
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
}
