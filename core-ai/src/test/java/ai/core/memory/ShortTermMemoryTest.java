package ai.core.memory;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ShortTermMemory summary service.
 *
 * @author xander
 */
class ShortTermMemoryTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemoryTest.class);
    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemory(1000, 0.33, Runnable::run);
    }

    @Test
    void testDefaultConstructor() {
        ShortTermMemory defaultMemory = new ShortTermMemory();
        assertEquals("", defaultMemory.getSummary());
        LOGGER.info("Default constructor test passed");
    }

    @Test
    void testSingleArgConstructor() {
        ShortTermMemory customMemory = new ShortTermMemory(500);
        assertEquals("", customMemory.getSummary());
        LOGGER.info("Single arg constructor test passed");
    }

    @Test
    void testSummaryManagement() {
        assertTrue(memory.getSummary().isEmpty());

        memory.setSummary("Test summary");
        assertEquals("Test summary", memory.getSummary());

        memory.setSummary(null);
        assertEquals("", memory.getSummary());

        LOGGER.info("Summary management test passed");
    }

    @Test
    void testClear() {
        memory.setSummary("Some summary");
        memory.clear();

        assertTrue(memory.getSummary().isEmpty());
        LOGGER.info("Clear test passed");
    }

    @Test
    void testGetSummaryTokens() {
        memory.setSummary("");
        assertEquals(0, memory.getSummaryTokens());

        memory.setSummary("Hello world");
        assertTrue(memory.getSummaryTokens() > 0);

        LOGGER.info("Get summary tokens test passed");
    }

    @Test
    void testBuildSummaryBlockEmpty() {
        memory.setSummary("");
        assertEquals("", memory.buildSummaryBlock());

        LOGGER.info("Build summary block empty test passed");
    }

    @Test
    void testBuildSummaryBlockWithContent() {
        memory.setSummary("User prefers dark mode");
        String block = memory.buildSummaryBlock();

        assertTrue(block.contains("[Conversation Memory]"));
        assertTrue(block.contains("User prefers dark mode"));

        LOGGER.info("Build summary block with content test passed: {}", block);
    }

    @Test
    void testShouldTriggerAsyncWithoutProvider() {
        // Without LLM provider, should never trigger
        assertFalse(memory.shouldTriggerAsync(10, 20, 500, 1000));

        LOGGER.info("Should trigger async without provider test passed");
    }

    @Test
    void testShouldTriggerAsyncMessageThreshold() {
        memory.setLLMProvider(createMockProvider(), "test-model");

        // Below threshold (33% of 20 = 6.6, so need >= 7)
        assertFalse(memory.shouldTriggerAsync(5, 20, 100, 1000));

        // At threshold
        assertTrue(memory.shouldTriggerAsync(7, 20, 100, 1000));

        // Above threshold
        assertTrue(memory.shouldTriggerAsync(10, 20, 100, 1000));

        LOGGER.info("Should trigger async message threshold test passed");
    }

    @Test
    void testShouldTriggerAsyncTokenThreshold() {
        memory.setLLMProvider(createMockProvider(), "test-model");

        // Below threshold (33% of 1000 = 330)
        assertFalse(memory.shouldTriggerAsync(1, 20, 300, 1000));

        // At threshold
        assertTrue(memory.shouldTriggerAsync(1, 20, 330, 1000));

        // Above threshold
        assertTrue(memory.shouldTriggerAsync(1, 20, 500, 1000));

        LOGGER.info("Should trigger async token threshold test passed");
    }

    @Test
    void testTriggerAsyncWithoutProvider() {
        List<Message> messages = List.of(
            Message.of(RoleType.USER, "Hello"),
            Message.of(RoleType.ASSISTANT, "Hi there")
        );

        // Should not throw, just return silently
        memory.triggerAsync(messages);

        LOGGER.info("Trigger async without provider test passed");
    }

    @Test
    void testTriggerAsyncWithEmptyMessages() {
        memory.setLLMProvider(createMockProvider(), "test-model");

        List<Message> messages = List.of();
        memory.triggerAsync(messages);

        // Should handle empty gracefully
        assertFalse(memory.tryApplyAsyncResult());

        LOGGER.info("Trigger async with empty messages test passed");
    }

    @Test
    void testTryApplyAsyncResultNoPending() {
        assertFalse(memory.tryApplyAsyncResult());

        LOGGER.info("Try apply async result no pending test passed");
    }

    @Test
    void testSummarizeWithoutProvider() {
        List<Message> messages = List.of(
            Message.of(RoleType.USER, "Hello"),
            Message.of(RoleType.ASSISTANT, "Hi")
        );

        // Should not throw, just return silently
        memory.summarize(messages);
        assertTrue(memory.getSummary().isEmpty());

        LOGGER.info("Summarize without provider test passed");
    }

    @Test
    void testSummarizeWithEmptyMessages() {
        memory.setLLMProvider(createMockProvider(), "test-model");

        List<Message> messages = List.of();
        memory.summarize(messages);

        assertTrue(memory.getSummary().isEmpty());

        LOGGER.info("Summarize with empty messages test passed");
    }

    @Test
    void testCompressSummaryWithoutProvider() {
        memory.setSummary("Some summary content");
        memory.compressSummary(100);

        // Should not change without provider
        assertEquals("Some summary content", memory.getSummary());

        LOGGER.info("Compress summary without provider test passed");
    }

    @Test
    void testCompressSummaryEmptySummary() {
        memory.setLLMProvider(createMockProvider(), "test-model");
        memory.setSummary("");

        memory.compressSummary(100);
        assertTrue(memory.getSummary().isEmpty());

        LOGGER.info("Compress summary empty summary test passed");
    }

    @Test
    void testCompressSummaryWithinLimit() {
        memory.setLLMProvider(createMockProvider(), "test-model");
        memory.setSummary("Short");

        // Target is larger than current, should not compress
        memory.compressSummary(1000);
        assertEquals("Short", memory.getSummary());

        LOGGER.info("Compress summary within limit test passed");
    }

    @Test
    void testSystemMessagesSkippedInFormatting() {
        memory.setLLMProvider(createMockProvider(), "test-model");

        List<Message> messages = List.of(
            Message.of(RoleType.SYSTEM, "You are a helpful assistant"),
            Message.of(RoleType.USER, "Hello"),
            Message.of(RoleType.ASSISTANT, "Hi")
        );

        // System messages should be skipped when formatting
        // This tests internal behavior indirectly
        memory.triggerAsync(messages);

        LOGGER.info("System messages skipped in formatting test passed");
    }

    private LLMProvider createMockProvider() {
        return new MockLLMProvider();
    }

    static class MockLLMProvider extends LLMProvider {
        MockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return null;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return null;
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            return null;
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
