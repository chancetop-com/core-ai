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
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.history.InMemoryChatHistoryStore;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

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
            // 1. Create stores - user implements their own for production
            var memoryStore = new InMemoryStore();
            var chatHistoryStore = new InMemoryChatHistoryStore();

            // 2. Create long-term memory with default extractor
            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .chatHistoryStore(chatHistoryStore)
                .build();

            // 3. Create agent with unified memory
            Agent agent = Agent.builder()
                .name("assistant")
                .llmProvider(llmProvider)
                .unifiedMemory(longTermMemory)  // Auto-registers MemoryRecallTool
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
            var chatHistoryStore = new InMemoryChatHistoryStore();

            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .chatHistoryStore(chatHistoryStore)
                .config(LongTermMemoryConfig.builder()
                    .maxBufferTurns(3)            // Extract after every 3 user turns
                    .asyncExtraction(false)        // Sync extraction for testing
                    .extractOnSessionEnd(true)     // Extract remaining on session end
                    .build())
                .build();

            Agent agent = Agent.builder()
                .name("assistant")
                .llmProvider(llmProvider)
                .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
                    .maxRecallRecords(10)         // Return max 10 memories
                    .autoRecall(true)              // Register MemoryRecallTool
                    .build())
                .build();

            assertNotNull(agent);
        }
    }

    @Nested
    @DisplayName("Memory with Custom Extractor")
    class MemoryWithCustomExtractor {

        @Test
        @DisplayName("Use custom memory extractor")
        void useCustomExtractor() {
            var memoryStore = new InMemoryStore();
            var chatHistoryStore = new InMemoryChatHistoryStore();

            // Custom extractor that extracts user preferences
            MemoryExtractor customExtractor = messages -> {
                List<MemoryRecord> records = new ArrayList<>();
                for (Message msg : messages) {
                    // Simple extraction: look for "I like" or "I prefer" in user messages
                    if (msg.role == RoleType.USER && msg.content != null
                        && (msg.content.contains("like") || msg.content.contains("prefer"))) {
                        records.add(MemoryRecord.builder()
                            .content("User preference: " + msg.content)
                            .importance(0.8)
                            .build());
                    }
                }
                return records;
            };

            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .chatHistoryStore(chatHistoryStore)
                .extractor(customExtractor)
                .config(LongTermMemoryConfig.builder()
                    .maxBufferTurns(2)
                    .asyncExtraction(false)
                    .build())
                .build();

            Agent agent = Agent.builder()
                .name("assistant")
                .llmProvider(llmProvider)
                .unifiedMemory(longTermMemory)
                .build();

            assertNotNull(agent);
        }
    }

    @Nested
    @DisplayName("Production-like Setup")
    class ProductionSetup {

        @Test
        @DisplayName("Per-user memory isolation pattern")
        void perUserMemoryIsolation() {
            // In production, create separate LongTermMemory per user
            String userId = "user-123";

            // Each user gets their own stores
            // In production: new MilvusMemoryStore(userId), new RedisChatHistoryStore(userId)
            var userMemoryStore = new InMemoryStore();
            var userChatHistoryStore = new InMemoryChatHistoryStore();

            LongTermMemory userMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .memoryStore(userMemoryStore)
                .chatHistoryStore(userChatHistoryStore)
                .config(LongTermMemoryConfig.builder()
                    .maxBufferTurns(5)
                    .asyncExtraction(true)  // Use async in production
                    .extractOnSessionEnd(true)
                    .build())
                .build();

            Agent agent = Agent.builder()
                .name("personalized-assistant")
                .systemPrompt("You are a personalized assistant. Use the memory tool to recall user preferences.")
                .llmProvider(llmProvider)
                .unifiedMemory(userMemory)
                .build();

            assertNotNull(agent);

            // Start session for this user
            userMemory.startSession("session-" + userId + "-" + System.currentTimeMillis());
            userMemory.endSession();
        }

        @Test
        @DisplayName("Agent with both short-term and long-term memory")
        void agentWithBothMemories() {
            var memoryStore = new InMemoryStore();
            var chatHistoryStore = new InMemoryChatHistoryStore();

            LongTermMemory longTermMemory = LongTermMemory.builder()
                .llmProvider(llmProvider)
                .memoryStore(memoryStore)
                .chatHistoryStore(chatHistoryStore)
                .build();

            // Both memories enabled
            Agent agent = Agent.builder()
                .name("full-memory-assistant")
                .llmProvider(llmProvider)
                .enableShortTermMemory(true)   // Conversation compression
                .unifiedMemory(longTermMemory)  // Cross-session memory
                .build();

            assertNotNull(agent);
            // Short-term: handles conversation context within session
            // Long-term: remembers user preferences across sessions
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
