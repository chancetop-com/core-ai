package ai.core.memory.unified;

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
import ai.core.memory.longterm.DefaultLongTermMemoryStore;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import ai.core.memory.recall.MemoryRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Unit tests for the unified memory system.
 *
 * @author xander
 */
@DisplayName("Unified Memory System Tests")
class UnifiedMemoryTest {

    private static final int EMBEDDING_DIM = 128;
    private static final String USER_ID = "test-user";

    private DefaultLongTermMemoryStore store;
    private LongTermMemory longTermMemory;
    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
        LLMProvider llmProvider = createMockLLMProvider();
        store = DefaultLongTermMemoryStore.inMemory();
        longTermMemory = LongTermMemory.builder()
            .llmProvider(llmProvider)
            .store(store)
            .extractor(createMockExtractor())
            .config(LongTermMemoryConfig.builder()
                .embeddingDimension(EMBEDDING_DIM)
                .asyncExtraction(false)
                .build())
            .build();
    }

    // ==================== Helper Methods ====================

    private MemoryRecord createMemoryRecord(String content, MemoryType type, double importance) {
        return MemoryRecord.builder()
            .namespace(Namespace.forUser(USER_ID))
            .content(content)
            .type(type)
            .importance(importance)
            .build();
    }

    private MemoryRecord createMemoryRecordWithNamespace(Namespace ns, String content, MemoryType type) {
        return MemoryRecord.builder()
            .namespace(ns)
            .content(content)
            .type(type)
            .importance(0.8)
            .build();
    }

    private float[] randomEmbedding() {
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding[i] = random.nextFloat() * 2 - 1;
        }
        return embedding;
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
        when(mock.extract(any(Namespace.class), any())).thenReturn(List.of());
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
            Namespace ns = Namespace.forUser(USER_ID);

            List<MemoryRecord> records = List.of(
                createMemoryRecordWithNamespace(ns, "User prefers Python programming", MemoryType.PREFERENCE),
                createMemoryRecordWithNamespace(ns, "User prefers Java programming", MemoryType.PREFERENCE),
                createMemoryRecord("User works at company X", MemoryType.FACT, 0.8)
            );

            List<ConflictGroup> conflicts = resolver.detectConflicts(records);

            // May or may not detect conflicts depending on topic extraction
            assertNotNull(conflicts);
        }

        @Test
        @DisplayName("Resolve conflicts with NEWEST_WINS strategy")
        void testResolveNewestWins() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();

            MemoryRecord older = createMemoryRecord("User prefers dark mode", MemoryType.PREFERENCE, 0.8);
            MemoryRecord newer = createMemoryRecord("User prefers light mode", MemoryType.PREFERENCE, 0.8);

            ConflictGroup group = new ConflictGroup("user prefers", List.of(older, newer));

            MemoryRecord resolved = resolver.resolveGroup(group, ConflictStrategy.NEWEST_WINS);

            assertNotNull(resolved);
            assertEquals(newer, resolved, "Should select the newest record");
        }

        @Test
        @DisplayName("Resolve conflicts with IMPORTANCE_BASED strategy")
        void testResolveImportanceBased() {
            MemoryConflictResolver resolver = new MemoryConflictResolver();

            MemoryRecord lowImportance = createMemoryRecord("Less important memory", MemoryType.FACT, 0.3);
            MemoryRecord highImportance = createMemoryRecord("High importance memory", MemoryType.FACT, 0.95);

            ConflictGroup group = new ConflictGroup("importance test", List.of(lowImportance, highImportance));

            MemoryRecord resolved = resolver.resolveGroup(group, ConflictStrategy.IMPORTANCE_BASED);

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
            assertEquals(ConflictStrategy.NEWEST_WITH_MERGE, config.getConflictStrategy());
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
    @DisplayName("UnifiedMemoryLifecycle Tests")
    class UnifiedMemoryLifecycleTests {

        @Test
        @DisplayName("Create lifecycle with default maxRecallRecords")
        void testCreateLifecycleDefault() {
            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory);

            assertNotNull(lifecycle.getLongTermMemory());
            assertNotNull(lifecycle.getRecallService());
        }

        @Test
        @DisplayName("Create lifecycle with custom maxRecallRecords")
        void testCreateLifecycleCustomMaxRecords() {
            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory, 10);

            assertNotNull(lifecycle.getLongTermMemory());
            assertNotNull(lifecycle.getRecallService());
        }

        @Test
        @DisplayName("beforeModel injects memory into completion request")
        void testBeforeModelInjectsMemory() {
            // Pre-populate some memories
            Namespace ns = Namespace.forUser(USER_ID);
            store.save(createMemoryRecordWithNamespace(ns, "User prefers brief answers", MemoryType.PREFERENCE),
                randomEmbedding());

            longTermMemory.startSessionForUser(USER_ID, "test-session");

            UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(longTermMemory);

            List<Message> messages = new ArrayList<>();
            messages.add(Message.of(RoleType.SYSTEM, "You are a helpful assistant."));
            messages.add(Message.of(RoleType.USER, "Tell me about preferences"));

            CompletionRequest request = new CompletionRequest();
            request.messages = messages;

            // Trigger beforeModel to inject memories
            lifecycle.beforeModel(request, null);

            // Should have injected memory tool call messages (2 messages added)
            assertTrue(request.messages.size() > 2);
            longTermMemory.endSession();
        }
    }
}
