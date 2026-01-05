package ai.core.agent.memory;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.llm.LLMProviderConfig;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryStore;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import ai.core.tool.tools.MemoryRecallTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Agent's use of search_memory_tool (MemoryRecallTool).
 *
 * <p>Tests the following scenarios:
 * <ul>
 *   <li>LLM proactively calls search_memory_tool</li>
 *   <li>Tool returns formatted memories to LLM</li>
 *   <li>Tool handles empty results gracefully</li>
 *   <li>Tool respects namespace isolation</li>
 * </ul>
 *
 * @author xander
 */
@DisplayName("Agent Memory Tool Tests")
class AgentMemoryToolTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryToolTest.class);
    private static final int EMBEDDING_DIM = 128;
    private static final String USER_ID = "test-user";
    private static final String SESSION_ID = "test-session";

    private LLMProvider mockLlmProvider;
    private LongTermMemory longTermMemory;
    private MemoryStore store;
    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
        mockLlmProvider = createMockLLMProvider();
        store = new InMemoryStore();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(mockLlmProvider)
            .store(store)
            .extractor(createMockExtractor())
            .config(LongTermMemoryConfig.builder()
                .asyncExtraction(false)
                .build())
            .build();
    }

    @Test
    @DisplayName("MemoryRecallTool executes successfully and returns formatted memories")
    void testMemoryRecallToolExecution() {
        // Pre-store memories
        MemoryScope scope = MemoryScope.forUser(USER_ID);
        store.save(createMemoryRecord(scope, "User prefers dark mode", 0.9),
            randomEmbedding());
        store.save(createMemoryRecord(scope, "User is learning Python", 0.8),
            randomEmbedding());

        // Create tool
        MemoryRecallTool tool = MemoryRecallTool.builder()
            .longTermMemory(longTermMemory)
            .maxRecords(5)
            .build();
        tool.setCurrentScope(scope);

        // Execute tool
        var result = tool.execute("{\"query\": \"user preferences\"}");

        assertTrue(result.isCompleted(), "Tool should complete successfully");
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("[User Memory]"), "Result should contain memory header");
        LOGGER.info("Tool result: {}", result.getResult());
    }

    @Test
    @DisplayName("MemoryRecallTool returns no memories message when empty")
    void testMemoryRecallToolEmptyResult() {
        MemoryScope scope = MemoryScope.forUser(USER_ID);

        MemoryRecallTool tool = MemoryRecallTool.builder()
            .longTermMemory(longTermMemory)
            .maxRecords(5)
            .build();
        tool.setCurrentScope(scope);

        var result = tool.execute("{\"query\": \"something that doesn't exist\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("No relevant memories"),
            "Should indicate no memories found");
        LOGGER.info("Empty result: {}", result.getResult());
    }

    @Test
    @DisplayName("MemoryRecallTool retrieves memories by importance")
    void testMemoryRecallToolByImportance() {
        MemoryScope scope = MemoryScope.forUser(USER_ID);

        // Store memories with different importance
        store.save(createMemoryRecord(scope, "User prefers dark mode", 0.9),
            randomEmbedding());
        store.save(createMemoryRecord(scope, "User works at TechCorp", 0.8),
            randomEmbedding());
        store.save(createMemoryRecord(scope, "User wants to learn AI", 0.85),
            randomEmbedding());

        MemoryRecallTool tool = MemoryRecallTool.builder()
            .longTermMemory(longTermMemory)
            .maxRecords(10)
            .build();
        tool.setCurrentScope(scope);

        // Query memories
        var result = tool.execute("{\"query\": \"user info\"}");

        assertTrue(result.isCompleted());
        LOGGER.info("Retrieved result: {}", result.getResult());
    }

    @Test
    @DisplayName("Agent with LongTermMemory has search_memory_tool available")
    void testAgentHasMemoryTool() {
        Agent agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(mockLlmProvider)
            .systemPrompt("You are a helpful assistant.")
            .unifiedMemory(longTermMemory)
            .build();

        var tools = agent.getToolCalls();
        boolean hasMemoryTool = tools.stream()
            .anyMatch(t -> MemoryRecallTool.TOOL_NAME.equals(t.getName()));

        assertTrue(hasMemoryTool, "Agent should have search_memory_tool");
        assertEquals("search_memory_tool", MemoryRecallTool.TOOL_NAME);

        // Find the tool and verify its properties
        var memoryTool = tools.stream()
            .filter(t -> t instanceof MemoryRecallTool)
            .map(t -> (MemoryRecallTool) t)
            .findFirst()
            .orElseThrow();

        assertNotNull(memoryTool.getDescription());
        assertFalse(memoryTool.getParameters().isEmpty());
        LOGGER.info("Memory tool description: {}", memoryTool.getDescription());
    }

    @Test
    @DisplayName("LLM can call search_memory_tool during agent execution")
    void testLLMCallsMemoryTool() {
        // Pre-store memory
        MemoryScope scope = MemoryScope.forUser(USER_ID);
        store.save(createMemoryRecord(scope, "User prefers concise answers", 0.9),
            randomEmbedding());

        // Create mock that simulates LLM calling search_memory_tool
        LLMProvider toolCallingLlm = createToolCallingMockLLM();

        Agent agent = Agent.builder()
            .name("tool-calling-agent")
            .llmProvider(toolCallingLlm)
            .systemPrompt("""
                You are a helpful assistant with memory.
                Use search_memory_tool to recall user preferences.
                """)
            .unifiedMemory(longTermMemory)
            .build();

        ExecutionContext context = ExecutionContext.builder()
            .userId(USER_ID)
            .sessionId(SESSION_ID)
            .build();

        String response = agent.run("What do you know about my preferences?", context);

        assertNotNull(response);
        LOGGER.info("Agent response after tool call: {}", response);
    }

    @Test
    @DisplayName("Memory tool namespace is set correctly from ExecutionContext")
    void testNamespaceFromExecutionContext() {
        Agent agent = Agent.builder()
            .name("namespace-test-agent")
            .llmProvider(mockLlmProvider)
            .systemPrompt("You are a helpful assistant.")
            .unifiedMemory(longTermMemory)
            .build();

        // Find the memory tool
        var memoryTool = agent.getToolCalls().stream()
            .filter(t -> t instanceof MemoryRecallTool)
            .map(t -> (MemoryRecallTool) t)
            .findFirst()
            .orElseThrow();

        // Before agent run, scope should be null
        assertNull(memoryTool.getCurrentScope());

        // After agent run with context, scope should be set
        ExecutionContext context = ExecutionContext.builder()
            .userId(USER_ID)
            .sessionId(SESSION_ID)
            .build();

        agent.run("Hello", context);

        // The lifecycle should have set the scope
        MemoryScope currentScope = memoryTool.getCurrentScope();
        assertNotNull(currentScope, "Scope should be set after agent run");
        assertEquals(MemoryScope.forUser(USER_ID), currentScope, "Scope should match user scope");
        LOGGER.info("Scope set to: {}", currentScope.toKey());
    }

    // ==================== Helper Methods ====================

    private MemoryRecord createMemoryRecord(MemoryScope scope, String content, double importance) {
        return MemoryRecord.builder()
            .scope(scope)
            .content(content)
            .importance(importance)
            .build();
    }

    private List<Double> randomEmbedding() {
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding.add((double) (random.nextFloat() * 2 - 1));
        }
        return embedding;
    }

    private LLMProvider createMockLLMProvider() {
        return new SimpleMockLLMProvider();
    }

    private LLMProvider createToolCallingMockLLM() {
        return new ToolCallingMockLLMProvider();
    }

    private MemoryExtractor createMockExtractor() {
        MemoryExtractor mock = mock(MemoryExtractor.class);
        when(mock.extract(any(MemoryScope.class), any())).thenReturn(List.of());
        return mock;
    }

    /**
     * Simple mock LLM provider that returns basic responses.
     */
    static class SimpleMockLLMProvider extends LLMProvider {
        private final AtomicInteger embCounter = new AtomicInteger(0);

        SimpleMockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            var response = new CompletionResponse();
            var choice = new Choice();
            choice.message = Message.of(RoleType.ASSISTANT, "Mock response");
            choice.finishReason = FinishReason.STOP;
            response.choices = List.of(choice);
            response.usage = createUsage();
            return response;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            List<EmbeddingResponse.EmbeddingData> dataList = new ArrayList<>();
            for (int i = 0; i < request.query().size(); i++) {
                float[] emb = new float[EMBEDDING_DIM];
                int seed = embCounter.incrementAndGet();
                Random r = new Random(seed);
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    emb[j] = r.nextFloat() * 2 - 1;
                }
                dataList.add(EmbeddingResponse.EmbeddingData.of(request.query().get(i), Embedding.of(emb)));
            }
            return EmbeddingResponse.of(dataList, null);
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
            return "simple-mock";
        }

        @Override
        public int maxTokens(String model) {
            return 8000;
        }

        @Override
        public int maxTokens() {
            return 8000;
        }

        private Usage createUsage() {
            Usage usage = new Usage();
            usage.setPromptTokens(10);
            usage.setCompletionTokens(20);
            usage.setTotalTokens(30);
            return usage;
        }
    }

    /**
     * Mock LLM provider that simulates tool calling behavior.
     */
    static class ToolCallingMockLLMProvider extends LLMProvider {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicInteger embCounter = new AtomicInteger(0);

        ToolCallingMockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            int count = callCount.incrementAndGet();
            var response = new CompletionResponse();
            var choice = new Choice();

            if (count == 1) {
                // First call: LLM decides to call search_memory_tool
                choice.message = Message.of(RoleType.ASSISTANT, null);
                choice.message.toolCalls = List.of(FunctionCall.of(
                    UUID.randomUUID().toString(),
                    "function",
                    MemoryRecallTool.TOOL_NAME,
                    "{\"query\": \"user preferences\"}"
                ));
                choice.finishReason = FinishReason.TOOL_CALLS;
            } else {
                // Second call: LLM responds after getting tool result
                choice.message = Message.of(RoleType.ASSISTANT,
                    "Based on your preferences, you like concise answers.");
                choice.finishReason = FinishReason.STOP;
            }

            response.choices = List.of(choice);
            response.usage = createUsage();
            return response;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            List<EmbeddingResponse.EmbeddingData> dataList = new ArrayList<>();
            for (int i = 0; i < request.query().size(); i++) {
                float[] emb = new float[EMBEDDING_DIM];
                int seed = embCounter.incrementAndGet();
                Random r = new Random(seed);
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    emb[j] = r.nextFloat() * 2 - 1;
                }
                dataList.add(EmbeddingResponse.EmbeddingData.of(request.query().get(i), Embedding.of(emb)));
            }
            return EmbeddingResponse.of(dataList, null);
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
            return "tool-calling-mock";
        }

        @Override
        public int maxTokens(String model) {
            return 8000;
        }

        @Override
        public int maxTokens() {
            return 8000;
        }

        private Usage createUsage() {
            Usage usage = new Usage();
            usage.setPromptTokens(10);
            usage.setCompletionTokens(20);
            usage.setTotalTokens(30);
            return usage;
        }
    }
}
