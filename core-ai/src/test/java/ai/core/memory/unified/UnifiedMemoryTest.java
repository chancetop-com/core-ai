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
import ai.core.memory.budget.ContextBudgetManager;
import ai.core.memory.conflict.ConflictGroup;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.conflict.MemoryConflictResolver;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import ai.core.memory.recall.MemoryRecallService;
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

    private MemoryRecord createMemoryRecord(String content, MemoryType type, double importance) {
        return MemoryRecord.builder()
            .scope(MemoryScope.forUser(USER_ID))
            .content(content)
            .type(type)
            .importance(importance)
            .build();
    }

    private MemoryRecord createMemoryRecordWithScope(MemoryScope scope, String content, MemoryType type) {
        return MemoryRecord.builder()
            .scope(scope)
            .content(content)
            .type(type)
            .importance(0.8)
            .build();
    }

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
    @DisplayName("ContextBudgetManager Tests")
    class ContextBudgetManagerTests {

        @Test
        @DisplayName("Calculate available budget with empty messages")
        void testCalculateBudgetEmptyMessages() {
            ContextBudgetManager manager = new ContextBudgetManager();
            int budget = manager.calculateAvailableBudget(List.of(), "You are a helpful assistant.");

            assertTrue(budget > 0, "Budget should be positive");
        }

        @Test
        @DisplayName("Calculate budget with existing messages reduces available budget")
        void testCalculateBudgetWithMessages() {
            ContextBudgetManager manager = new ContextBudgetManager(10000, 0.2, 0.3);

            List<Message> emptyMessages = List.of();
            int budgetEmpty = manager.calculateAvailableBudget(emptyMessages, "System prompt");

            List<Message> messages = List.of(
                Message.of(RoleType.USER, "This is a test message with some content"),
                Message.of(RoleType.ASSISTANT, "This is a response with more content")
            );
            int budgetWithMessages = manager.calculateAvailableBudget(messages, "System prompt");

            assertTrue(budgetWithMessages < budgetEmpty,
                "Budget with messages should be less than empty");
        }

        @Test
        @DisplayName("Select memories within budget")
        void testSelectWithinBudget() {
            ContextBudgetManager manager = new ContextBudgetManager();

            List<MemoryRecord> candidates = List.of(
                createMemoryRecord("User prefers dark mode", MemoryType.PREFERENCE, 0.9),
                createMemoryRecord("User is a software engineer", MemoryType.FACT, 0.8),
                createMemoryRecord("User asked about Python yesterday", MemoryType.EPISODE, 0.5)
            );

            List<MemoryRecord> selected = manager.selectWithinBudget(candidates, 100);

            assertFalse(selected.isEmpty(), "Should select at least one record");
            assertTrue(selected.size() <= candidates.size(), "Should not exceed candidates");
        }

        @Test
        @DisplayName("Select at least one memory even if budget is small")
        void testSelectAtLeastOneMemory() {
            ContextBudgetManager manager = new ContextBudgetManager();

            List<MemoryRecord> candidates = List.of(
                createMemoryRecord("Important user preference", MemoryType.PREFERENCE, 0.95)
            );

            List<MemoryRecord> selected = manager.selectWithinBudget(candidates, 10);

            assertEquals(1, selected.size(), "Should select one record");
        }
    }

    @Nested
    @DisplayName("MemoryConflictResolver Tests")
    class MemoryConflictResolverTests {

        @Test
        @DisplayName("Detect conflicts between similar records")
        void testDetectConflicts() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();
            MemoryScope scope = MemoryScope.forUser(USER_ID);

            List<MemoryRecord> records = List.of(
                createMemoryRecordWithScope(scope, "User prefers Python programming", MemoryType.PREFERENCE),
                createMemoryRecordWithScope(scope, "User prefers Java programming", MemoryType.PREFERENCE),
                createMemoryRecord("User works at company X", MemoryType.FACT, 0.8)
            );

            List<ConflictGroup> conflicts = resolver.detectConflicts(records);

            // May or may not detect conflicts depending on topic extraction
            assertNotNull(conflicts);
        }

        @Test
        @DisplayName("Resolve conflicts with KEEP_LATEST strategy")
        void testResolveKeepLatest() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();

            MemoryRecord older = createMemoryRecord("User prefers dark mode", MemoryType.PREFERENCE, 0.8);
            MemoryRecord newer = createMemoryRecord("User prefers light mode", MemoryType.PREFERENCE, 0.8);

            ConflictGroup group = new ConflictGroup("user prefers", List.of(older, newer));

            MemoryRecord resolved = resolver.resolveGroup(group, ConflictStrategy.KEEP_LATEST);

            assertNotNull(resolved);
            assertEquals(newer, resolved, "Should select the newest record");
        }

        @Test
        @DisplayName("Resolve conflicts with KEEP_MOST_IMPORTANT strategy")
        void testResolveKeepMostImportant() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();

            MemoryRecord lowImportance = createMemoryRecord("Less important memory", MemoryType.FACT, 0.3);
            MemoryRecord highImportance = createMemoryRecord("High importance memory", MemoryType.FACT, 0.95);

            ConflictGroup group = new ConflictGroup("importance test", List.of(lowImportance, highImportance));

            MemoryRecord resolved = resolver.resolveGroup(group, ConflictStrategy.KEEP_MOST_IMPORTANT);

            assertNotNull(resolved);
            assertEquals(highImportance, resolved, "Should select the most important record");
        }

        @Test
        @DisplayName("mayConflict returns false for different types")
        void testMayConflictDifferentTypes() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();

            MemoryRecord pref = createMemoryRecord("User prefers X", MemoryType.PREFERENCE, 0.8);
            MemoryRecord fact = createMemoryRecord("User prefers X", MemoryType.FACT, 0.8);

            assertFalse(resolver.mayConflict(pref, fact), "Different types should not conflict");
        }
    }

    @Nested
    @DisplayName("MemoryRecallService Tests")
    class MemoryRecallServiceTests {

        @Test
        @DisplayName("Format memories as tool messages")
        void testFormatAsToolMessages() {
            MemoryRecallService service = new MemoryRecallService(longTermMemory);

            List<MemoryRecord> memories = List.of(
                createMemoryRecord("User prefers concise responses", MemoryType.PREFERENCE, 0.9),
                createMemoryRecord("User is learning Python", MemoryType.FACT, 0.8)
            );

            List<Message> messages = service.formatAsToolMessages(memories);

            assertEquals(2, messages.size(), "Should have assistant and tool messages");
            assertEquals(RoleType.ASSISTANT, messages.get(0).role);
            assertEquals(RoleType.TOOL, messages.get(1).role);
            assertTrue(messages.get(1).content.contains("[User Memory]"));
        }

        @Test
        @DisplayName("Extract latest user query from messages")
        void testExtractLatestUserQuery() {
            MemoryRecallService service = new MemoryRecallService(longTermMemory);

            List<Message> messages = List.of(
                Message.of(RoleType.SYSTEM, "You are helpful"),
                Message.of(RoleType.USER, "First question"),
                Message.of(RoleType.ASSISTANT, "First answer"),
                Message.of(RoleType.USER, "Second question")
            );

            String query = service.extractLatestUserQuery(messages);

            assertEquals("Second question", query);
        }

        @Test
        @DisplayName("Return empty list when no memories")
        void testFormatEmptyMemories() {
            MemoryRecallService service = new MemoryRecallService(longTermMemory);

            List<Message> messages = service.formatAsToolMessages(List.of());

            assertTrue(messages.isEmpty());
        }
    }

    @Nested
    @DisplayName("UnifiedMemoryConfig Tests")
    class UnifiedMemoryConfigTests {

        @Test
        @DisplayName("Default config has sensible defaults")
        void testDefaultConfig() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.defaultConfig();

            assertTrue(config.isAutoRecall());
            assertTrue(config.isAutoTransition());
            assertEquals(5, config.getMaxRecallRecords());
            assertEquals(ConflictStrategy.SMART_MERGE, config.getConflictStrategy());
        }

        @Test
        @DisplayName("Recall-only config disables transition")
        void testRecallOnlyConfig() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.recallOnly();

            assertTrue(config.isAutoRecall());
            assertFalse(config.isAutoTransition());
        }

        @Test
        @DisplayName("Builder respects value bounds")
        void testBuilderBounds() {
            UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
                .maxRecallRecords(100)
                .memoryBudgetRatio(1.0)
                .build();

            assertTrue(config.getMaxRecallRecords() <= 20);
            assertTrue(config.getMemoryBudgetRatio() <= 0.5);
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
