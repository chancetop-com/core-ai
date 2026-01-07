package ai.core.agent.memory;

import ai.core.agent.Agent;
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
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.history.ChatRecord;
import ai.core.memory.history.InMemoryChatHistoryProvider;
import ai.core.memory.InMemoryStore;
import ai.core.memory.Memory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
@Disabled
@DisplayName("Agent Memory Best Practices")
class AgentMemoryBestPracticeTest {

    private MockLLMProvider llmProvider;

    @BeforeEach
    void setUp() {
        llmProvider = new MockLLMProvider();
    }

    @Nested
    @DisplayName("Basic Agent with Long-term Memory")
    class BasicAgentWithMemory {

        @Test
        @DisplayName("Create agent with long-term memory using default config")
        void createAgentWithDefaultConfig() {
            // 1. Create stores
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
                .unifiedMemory(memory, UnifiedMemoryConfig.builder()
                    .maxRecallRecords(10)         // Return max 10 memories
                    .autoRecall(true)              // Register MemoryRecallTool
                    .build())
                .build();

            assertNotNull(agent);
        }
    }

    @Nested
    @DisplayName("Production-like Setup")
    class ProductionSetup {

        @Test
        @DisplayName("Extraction and Memory separation pattern")
        void extractionAndMemorySeparation() {
            String userId = "user-123";

            // Shared store and history provider
            var memoryStore = new InMemoryStore();
            var historyProvider = new InMemoryChatHistoryProvider();

            // 1. Extraction - run separately (e.g., end of session, scheduled task)
            Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);

            // 2. Memory - only for retrieval, used by Agent
            Memory memory = Memory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .build();

            // 3. Agent only uses Memory
            Agent agent = Agent.builder()
                .name("personalized-assistant")
                .systemPrompt("You are a personalized assistant. Use the memory tool to recall user preferences.")
                .llmProvider(llmProvider)
                .unifiedMemory(memory)
                .build();

            assertNotNull(agent);

            // Simulate conversation - user's system stores chat history
            historyProvider.addRecord(userId, ChatRecord.user("I prefer dark mode", Instant.now()));
            historyProvider.addRecord(userId, ChatRecord.assistant("Noted!", Instant.now()));

            // Trigger extraction separately (e.g., at end of session)
            extraction.run(userId);
        }

        @Test
        @DisplayName("Agent with both compression and long-term memory")
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
                .enableCompression(true)   // Conversation compression (within session)
                .unifiedMemory(memory)     // Cross-session memory
                .build();
//            agent.run()

            assertNotNull(agent);
            // Compression: handles conversation context within session
            // Memory: remembers user preferences across sessions
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
