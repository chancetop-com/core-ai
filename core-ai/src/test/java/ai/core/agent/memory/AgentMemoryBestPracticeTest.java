package ai.core.agent.memory;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.memory.Extraction;
import ai.core.memory.InMemoryStore;
import ai.core.memory.Memory;
import ai.core.memory.MemoryConfig;
import ai.core.memory.MemoryRecord;
import ai.core.memory.history.ChatRecord;
import ai.core.memory.history.InMemoryChatHistoryProvider;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.MemoryRecallTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Best practice tests for Agent Memory system.
 * Demonstrates proper usage patterns for Memory, Extraction, and ExecutionContext.
 *
 * @author xander
 */
@DisplayName("Agent Memory Best Practices")
class AgentMemoryBestPracticeTest {

    private MockLLMProvider llmProvider;

    @BeforeEach
    void setUp() {
        llmProvider = new MockLLMProvider();
    }

    private List<Double> generateTestEmbedding() {
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            embedding.add(Math.random());
        }
        return embedding;
    }

    @Nested
    @DisplayName("Basic Agent with Memory")
    class BasicAgentWithMemory {

        @Test
        @DisplayName("Create agent with memory using default config")
        void createAgentWithDefaultConfig() {
            // 1. Create memory store
            var memoryStore = new InMemoryStore();

            // 2. Create memory (only for retrieval)
            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            // 3. Create agent with memory
            Agent agent = Agent.builder()
                .name("assistant")
                .llmProvider(llmProvider)
                .unifiedMemory(memory)  // Auto-registers MemoryRecallTool
                .build();

            assertNotNull(agent);
            // Agent now has MemoryRecallTool registered
            assertTrue(agent.getToolCalls().stream()
                .anyMatch(t -> t.getName().contains("memory")));
        }

        @Test
        @DisplayName("Create agent with custom memory config")
        void createAgentWithCustomConfig() {
            var memoryStore = new InMemoryStore();

            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .defaultTopK(10)
                .build();

            Agent agent = Agent.builder()
                .name("assistant")
                .llmProvider(llmProvider)
                .unifiedMemory(memory, MemoryConfig.builder()
                    .maxRecallRecords(10)         // Return max 10 memories
                    .autoRecall(true)              // Register MemoryRecallTool
                    .build())
                .build();

            assertNotNull(agent);
        }
    }

    @Nested
    @DisplayName("ExecutionContext Usage")
    class ExecutionContextUsage {

        @Test
        @DisplayName("Create ExecutionContext with userId")
        void createExecutionContextWithUserId() {
            String userId = "user-123";
            String sessionId = "session-456";

            ExecutionContext context = ExecutionContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .customVariable("preference", "dark_mode")
                .build();

            assertEquals(userId, context.getUserId());
            assertEquals(sessionId, context.getSessionId());
            assertEquals("dark_mode", context.getCustomVariable("preference"));
        }

        @Test
        @DisplayName("MemoryRecallTool requires ExecutionContext with userId")
        void memoryRecallToolRequiresContext() {
            var memoryStore = new InMemoryStore();

            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            MemoryRecallTool tool = MemoryRecallTool.builder()
                .memory(memory)
                .maxRecords(5)
                .build();

            // Without context - should fail
            ToolCallResult resultWithoutContext = tool.execute("{\"query\": \"test\"}");
            assertTrue(resultWithoutContext.isFailed());
            assertTrue(resultWithoutContext.getResult().contains("ExecutionContext"));

            // With context - should work (even if no memories found)
            ExecutionContext context = ExecutionContext.builder()
                .userId("user-123")
                .build();
            ToolCallResult resultWithContext = tool.execute("{\"query\": \"test\"}", context);
            assertTrue(resultWithContext.isCompleted());
        }

        @Test
        @DisplayName("MemoryRecallTool retrieves memories for specific user")
        void memoryRecallToolRetrievesForUser() {
            String userId = "user-123";
            var memoryStore = new InMemoryStore();

            // Pre-populate memory store with test data
            MemoryRecord record = MemoryRecord.builder()
                .content("User prefers dark mode")
                .importance(0.8)
                .build();
            List<Double> embedding = generateTestEmbedding();
            memoryStore.save(userId, record, embedding);

            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            MemoryRecallTool tool = MemoryRecallTool.builder()
                .memory(memory)
                .maxRecords(5)
                .build();

            ExecutionContext context = ExecutionContext.builder()
                .userId(userId)
                .build();

            ToolCallResult result = tool.execute("{\"query\": \"preferences\"}", context);
            assertTrue(result.isCompleted());
            assertTrue(result.getResult().contains("dark mode"));
        }
    }

    @Nested
    @DisplayName("Extraction and Memory Separation")
    class ExtractionAndMemorySeparation {

        @Test
        @DisplayName("Extraction and Memory use shared store")
        void extractionAndMemoryShareStore() {
            String userId = "user-123";

            // Shared store and history provider
            var memoryStore = new InMemoryStore();
            var historyProvider = new InMemoryChatHistoryProvider();

            // 1. Extraction - runs separately
            Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);

            // 2. Memory - only for retrieval
            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            // 3. Agent uses Memory
            Agent agent = Agent.builder()
                .name("personalized-assistant")
                .systemPrompt("You are a personalized assistant.")
                .llmProvider(llmProvider)
                .unifiedMemory(memory)
                .build();

            assertNotNull(agent);

            // Simulate conversation
            historyProvider.addRecord(userId, ChatRecord.user("I prefer dark mode", Instant.now()));
            historyProvider.addRecord(userId, ChatRecord.assistant("Noted!", Instant.now()));

            // Verify history is recorded
            assertEquals(2, historyProvider.loadForExtraction(userId).size());

            // Trigger extraction (in real usage, this runs at session end)
            extraction.run(userId);
        }

        @Test
        @DisplayName("Agent with both compression and memory")
        void agentWithBothMemories() {
            var memoryStore = new InMemoryStore();

            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            // Both memories enabled
            Agent agent = Agent.builder()
                .name("full-memory-assistant")
                .llmProvider(llmProvider)
                .compression(true)   // Within session
                .unifiedMemory(memory)     // Cross-session
                .build();

            assertNotNull(agent);
        }
    }

    @Nested
    @DisplayName("User Isolation")
    class UserIsolation {

        @Test
        @DisplayName("Different users have isolated memories")
        void differentUsersHaveIsolatedMemories() {
            var memoryStore = new InMemoryStore();
            String user1 = "user-1";
            String user2 = "user-2";

            // Save memories for different users
            MemoryRecord record1 = MemoryRecord.builder()
                .content("User 1 prefers light mode")
                .importance(0.8)
                .build();
            MemoryRecord record2 = MemoryRecord.builder()
                .content("User 2 prefers dark mode")
                .importance(0.8)
                .build();

            memoryStore.save(user1, record1, generateTestEmbedding());
            memoryStore.save(user2, record2, generateTestEmbedding());

            // Verify isolation
            assertEquals(1, memoryStore.count(user1));
            assertEquals(1, memoryStore.count(user2));

            List<MemoryRecord> user1Memories = memoryStore.findAll(user1);
            List<MemoryRecord> user2Memories = memoryStore.findAll(user2);

            assertTrue(user1Memories.getFirst().getContent().contains("light mode"));
            assertTrue(user2Memories.getFirst().getContent().contains("dark mode"));
        }
    }

    @Nested
    @DisplayName("Best Practice Patterns")
    class BestPracticePatterns {

        @Test
        @DisplayName("Stateless agent pattern")
        void statelessAgentPattern() {
            Agent agent = Agent.builder()
                .name("stateless")
                .llmProvider(llmProvider)
                .compression(false)  // Disable compression for stateless
                .build();

            assertNotNull(agent);
        }

        @Test
        @DisplayName("Session-only agent pattern")
        void sessionOnlyAgentPattern() {
            Agent agent = Agent.builder()
                .name("session-only")
                .llmProvider(llmProvider)
                .compression(true)  // Only compression, no long-term memory
                .build();

            assertNotNull(agent);
        }

        @Test
        @DisplayName("Personalized agent pattern")
        void personalizedAgentPattern() {
            var memoryStore = new InMemoryStore();

            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            Agent agent = Agent.builder()
                .name("personalized")
                .llmProvider(llmProvider)
                .compression(true)   // Within session
                .unifiedMemory(memory)     // Cross-session
                .build();

            assertNotNull(agent);
            assertTrue(agent.getToolCalls().stream()
                .anyMatch(t -> t.getName().contains("memory")));
        }
    }

    /**
     * Mock LLM provider for testing.
     */
    static class MockLLMProvider extends LLMProvider {
        private static final int EMBEDDING_DIM = 8;

        MockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            var response = new CompletionResponse();
            response.choices = List.of(Choice.of(
                FinishReason.STOP,
                Message.of(RoleType.ASSISTANT, "I understand. How can I help you?")
            ));
            response.usage = new Usage();
            return response;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            List<EmbeddingResponse.EmbeddingData> embeddings = new ArrayList<>();
            for (String text : request.query()) {
                float[] vec = new float[EMBEDDING_DIM];
                for (int i = 0; i < EMBEDDING_DIM; i++) {
                    vec[i] = (float) Math.random();
                }
                embeddings.add(EmbeddingResponse.EmbeddingData.of(text, Embedding.of(vec)));
            }
            return EmbeddingResponse.of(embeddings, null);
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            return null;
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            return null;
        }

        @Override
        public String name() {
            return "mock";
        }
    }
}
