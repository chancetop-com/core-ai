package ai.core.memory.unified;

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
import ai.core.memory.UnifiedMemoryLifecycle;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import ai.core.tool.tools.MemoryRecallTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the unified memory system.
 *
 * @author xander
 */
@DisplayName("Unified Memory System Tests")
class UnifiedMemoryTest {

    private static final int EMBEDDING_DIM = 128;
    private static final String USER_ID = "test-user";

    private LongTermMemory longTermMemory;

    @BeforeEach
    void setUp() {
        LLMProvider llmProvider = createMockLLMProvider();
        InMemoryStore store = new InMemoryStore();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProvider)
            .store(store)
            .extractor(createMockExtractor())
            .config(LongTermMemoryConfig.builder()
                .asyncExtraction(false)
                .build())
            .build();
    }

    // ==================== Helper Methods ====================

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
            for (int i = 0; i < req.query().size(); i++) {
                float[] emb = new float[EMBEDDING_DIM];
                int seed = counter.incrementAndGet();
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

    // ==================== Test Classes ====================

    @Nested
    @DisplayName("UnifiedMemoryConfig Tests")
    class UnifiedMemoryConfigTests {

        @Test
        @DisplayName("Default config has sensible defaults")
        void testDefaultConfig() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.defaultConfig();

            assertTrue(config.isAutoRecall());
            assertEquals(5, config.getMaxRecallRecords());
        }

        @Test
        @DisplayName("Builder can disable auto recall")
        void testDisableAutoRecall() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
                .autoRecall(false)
                .build();

            assertFalse(config.isAutoRecall());
        }

        @Test
        @DisplayName("Builder respects value bounds")
        void testBuilderBounds() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
                .maxRecallRecords(100)
                .build();

            assertTrue(config.getMaxRecallRecords() <= 20);
        }
    }

    @Nested
    @DisplayName("MemoryRecallTool Tests")
    class MemoryRecallToolTests {

        @Test
        @DisplayName("Tool has correct name and description")
        void testToolMetadata() {
            var tool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(5)
                .build();

            assertEquals("search_memory_tool", tool.getName());
            assertNotNull(tool.getDescription());
            assertTrue(tool.getDescription().contains("memories"));
        }

        @Test
        @DisplayName("Tool requires query parameter")
        void testToolRequiresQuery() {
            var tool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(5)
                .build();

            // Execute without query
            var result = tool.execute("{}");

            assertTrue(result.getResult().contains("Error"));
        }

        @Test
        @DisplayName("Tool returns no context message when namespace not set")
        void testToolNoNamespace() {
            var tool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(5)
                .build();

            var result = tool.execute("{\"query\": \"user preferences\"}");

            assertTrue(result.getResult().contains("No user context"));
        }

        @Test
        @DisplayName("Tool returns no memories message when no matches found")
        void testToolNoMemoriesFound() {
            longTermMemory.startSessionForUser(USER_ID, "test-session");

            var tool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(5)
                .build();
            tool.setCurrentScope(MemoryScope.forUser(USER_ID));

            var result = tool.execute("{\"query\": \"user preferences\"}");

            assertTrue(result.getResult().contains("No relevant memories"));

            longTermMemory.endSession();
        }

        @Test
        @DisplayName("Tool respects maxRecords setting")
        void testToolMaxRecords() {
            var tool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(10)
                .build();

            assertEquals(10, tool.getMaxRecords());
        }

        @Test
        @DisplayName("Builder throws when longTermMemory not set")
        void testBuilderRequiresLongTermMemory() {
            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MemoryRecallTool.builder().build()
            );

            assertTrue(exception.getMessage().contains("longTermMemory"));
        }
    }

    @Nested
    @DisplayName("UnifiedMemoryLifecycle Tests")
    class UnifiedMemoryLifecycleTests {

        @Test
        @DisplayName("Create lifecycle with default maxRecallRecords")
        void testCreateLifecycleDefault() {
            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory);

            assertNotNull(lifecycle.getLongTermMemory());
            assertNotNull(lifecycle.getMemoryRecallTool());
            assertEquals(5, lifecycle.getMaxRecallRecords());
        }

        @Test
        @DisplayName("Create lifecycle with custom maxRecallRecords")
        void testCreateLifecycleCustomMaxRecords() {
            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory, 10);

            assertNotNull(lifecycle.getLongTermMemory());
            assertNotNull(lifecycle.getMemoryRecallTool());
            assertEquals(10, lifecycle.getMaxRecallRecords());
        }

        @Test
        @DisplayName("beforeModel sets namespace on memory recall tool")
        void testBeforeModelSetsNamespace() {
            longTermMemory.startSessionForUser(USER_ID, "test-session");

            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory);
            var recallTool = lifecycle.getMemoryRecallTool();

            // Create execution context with userId
            ExecutionContext context = ExecutionContext.builder()
                .userId(USER_ID)
                .sessionId("test-session")
                .build();

            // Before run sets namespace from context
            lifecycle.beforeAgentRun(new AtomicReference<>("test query"), context);

            CompletionRequest request = new CompletionRequest();
            request.messages = new ArrayList<>();
            request.messages.add(Message.of(RoleType.USER, "Tell me about preferences"));

            // beforeModel should set the namespace on the tool
            lifecycle.beforeModel(request, context);

            // Verify the tool now has the correct scope
            assertNotNull(recallTool.getCurrentScope());
            assertEquals(MemoryScope.forUser(USER_ID), recallTool.getCurrentScope());

            longTermMemory.endSession();
        }
    }
}
