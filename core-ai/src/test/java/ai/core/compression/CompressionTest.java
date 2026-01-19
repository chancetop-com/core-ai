package ai.core.compression;

import ai.core.agent.streaming.StreamingCallback;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class CompressionTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressionTest.class);
    private Compression compression;

    @BeforeEach
    void setUp() {
        compression = new Compression(0.7, 5, 10000, null, null, null);
    }

    @Test
    void testDefaultConstructor() {
        Compression defaultCompression = new Compression(createMockProvider(), "test-model");
        assertEquals(0.8, defaultCompression.getTriggerThreshold());
        assertEquals(5, defaultCompression.getKeepRecentTurns());
        LOGGER.info("Default constructor test passed");
    }

    @Test
    void testCustomConstructor() {
        Compression customCompression = new Compression(0.8, 3, createMockProvider(), "test-model", "test-model");
        assertEquals(0.8, customCompression.getTriggerThreshold());
        assertEquals(3, customCompression.getKeepRecentTurns());
        LOGGER.info("Custom constructor test passed");
    }

    @Test
    void testShouldCompressWithoutProvider() {
        assertFalse(compression.shouldCompress(100000));
        LOGGER.info("Should compress without provider test passed");
    }

    @Test
    void testShouldCompressWithProvider() {
        Compression compressionWithProvider = new Compression(0.7, 5, createMockProvider(), "test-model", "test-model");

        // Below threshold (70% of 128000 = 89600)
        assertFalse(compressionWithProvider.shouldCompress(50000));

        // Above threshold
        assertTrue(compressionWithProvider.shouldCompress(100000));

        LOGGER.info("Should compress with provider test passed");
    }

    @Test
    void testCompressWithoutProvider() {
        List<Message> messages = createTestMessages(10);
        List<Message> result = compression.compress(messages);

        // Without provider, should return original
        assertSame(messages, result);
        LOGGER.info("Compress without provider test passed");
    }

    @Test
    void testCompressWithEmptyMessages() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        List<Message> messages = List.of();
        List<Message> result = compressionWithProvider.compress(messages);

        assertSame(messages, result);
        LOGGER.info("Compress with empty messages test passed");
    }

    @Test
    void testCompressNotEnoughMessages() {
        Compression compressionWithProvider = new Compression(0.7, 5, createMockProvider(), "test-model", "test-model");
        // keepRecentTurns=5, so need more than 5*2=10 messages to compress
        List<Message> messages = createTestMessages(3);
        List<Message> result = compressionWithProvider.compress(messages);

        // Not enough messages to compress, return original
        assertSame(messages, result);
        LOGGER.info("Compress not enough messages test passed");
    }

    @Test
    void testCompressBelowThreshold() {
        Compression compressionWithProvider = new Compression(0.7, 5, createMockProvider(), "test-model", "test-model");
        // Create messages but tokens won't exceed threshold
        List<Message> messages = createTestMessages(15);
        List<Message> result = compressionWithProvider.compress(messages);

        // Below threshold, return original
        assertSame(messages, result);
        LOGGER.info("Compress below threshold test passed");
    }

    @Test
    void testCompressSuccessful() {
        // Create a compression with low threshold for testing
        Compression testCompression = new Compression(0.0001, 2,
            createMockProviderWithSummary("Test summary"), "test-model", "test-model");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(RoleType.SYSTEM, "You are helpful"));
        for (int i = 0; i < 10; i++) {
            messages.add(Message.of(RoleType.USER, "User message " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Assistant response " + i));
        }
        // Add final USER message (compression only triggers on new user input)
        messages.add(Message.of(RoleType.USER, "Final user message"));

        List<Message> result = testCompression.compress(messages);

        // Should have compressed
        assertTrue(result.size() < messages.size());
        // Should have system message first
        assertEquals(RoleType.SYSTEM, result.get(0).role);
        // Should have tool call message (ASSISTANT with toolCalls)
        assertEquals(RoleType.ASSISTANT, result.get(1).role);
        assertNotNull(result.get(1).toolCalls);
        assertEquals("memory_compress", result.get(1).toolCalls.getFirst().function.name);
        // Should have tool result message (TOOL with formatted summary)
        assertEquals(RoleType.TOOL, result.get(2).role);
        assertTrue(result.get(2).getTextContent().contains("[Previous Conversation Summary]"));
        assertTrue(result.get(2).getTextContent().contains("Test summary"));
        assertTrue(result.get(2).getTextContent().contains("[End Summary]"));

        LOGGER.info("Compress successful test passed: {} -> {} messages", messages.size(), result.size());
    }

    @Test
    void testSystemMessagePreserved() {
        Compression testCompression = new Compression(0.0001, 1,
            createMockProviderWithSummary("Summary"), "test-model", "test-model");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(RoleType.SYSTEM, "System prompt"));
        for (int i = 0; i < 5; i++) {
            messages.add(Message.of(RoleType.USER, "User " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Assistant " + i));
        }
        // Add final USER message (compression only triggers on new user input)
        messages.add(Message.of(RoleType.USER, "Final user message"));

        List<Message> result = testCompression.compress(messages);

        // System message should be first
        assertEquals(RoleType.SYSTEM, result.getFirst().role);
        assertEquals("System prompt", result.getFirst().getTextContent());

        LOGGER.info("System message preserved test passed");
    }

    private List<Message> createTestMessages(int turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(RoleType.SYSTEM, "You are a helpful assistant"));
        for (int i = 0; i < turns; i++) {
            messages.add(Message.of(RoleType.USER, "Hello " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Hi there " + i));
        }
        return messages;
    }

    private LLMProvider createMockProvider() {
        return new MockLLMProvider(null);
    }

    private LLMProvider createMockProviderWithSummary(String summary) {
        return new MockLLMProvider(summary);
    }

    // ==================== Tool Result Compression Tests ====================

    @Test
    void testCompressToolResultShortContent() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        String shortResult = "This is a short result";

        String compressed = compressionWithProvider.compressToolResult("test_tool", shortResult, "session-1");

        // Short content should not be compressed
        assertEquals(shortResult, compressed);
        LOGGER.info("Compress tool result short content test passed");
    }

    @Test
    void testCompressToolResultLongContent() throws Exception {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        // Create content that exceeds MAX_TOOL_RESULT_TOKENS (30000 tokens)
        // Each "word " is about 1 token, so we need >30000 words
        String longResult = "word ".repeat(100000);

        String compressed = compressionWithProvider.compressToolResult("search_tool", longResult, "session-123");

        // Should be compressed
        assertNotEquals(longResult, compressed);
        assertTrue(compressed.contains("[Tool result truncated"));
        assertTrue(compressed.contains("search_tool"));
        assertTrue(compressed.contains("HEAD (first 500 tokens)"));
        assertTrue(compressed.contains("TAIL (last 500 tokens)"));
        assertTrue(compressed.contains("session-123"));

        // Verify file was created
        String filePath = extractFilePath(compressed);
        assertNotNull(filePath);
        assertTrue(Files.exists(Path.of(filePath)));

        // Verify file content
        String fileContent = Files.readString(Path.of(filePath));
        assertEquals(longResult, fileContent);

        // Cleanup
        Files.deleteIfExists(Path.of(filePath));

        LOGGER.info("Compress tool result long content test passed");
    }

    @Test
    void testCompressToolResultNullContent() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");

        String compressed = compressionWithProvider.compressToolResult("test_tool", null, "session-1");

        assertSame(null, compressed);
        LOGGER.info("Compress tool result null content test passed");
    }

    @Test
    void testCompressToolResultEmptyContent() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");

        String compressed = compressionWithProvider.compressToolResult("test_tool", "", "session-1");

        assertEquals("", compressed);
        LOGGER.info("Compress tool result empty content test passed");
    }

    @Test
    void testShouldCompressToolResult() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");

        // Short content
        assertFalse(compressionWithProvider.shouldCompressToolResult("short"));

        // Long content (>30000 tokens)
        String longContent = "word ".repeat(100000);
        assertTrue(compressionWithProvider.shouldCompressToolResult(longContent));

        LOGGER.info("Should compress tool result test passed");
    }

    @Test
    void testCompressToolResultDefaultSession() throws Exception {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        String longResult = "word ".repeat(100000);

        String compressed = compressionWithProvider.compressToolResult("test_tool", longResult, null);

        // Should use "default" as session id
        assertTrue(compressed.contains("default"));

        // Cleanup
        String filePath = extractFilePath(compressed);
        if (filePath != null) {
            Files.deleteIfExists(Path.of(filePath));
        }

        LOGGER.info("Compress tool result default session test passed");
    }

    private String extractFilePath(String summary) {
        for (String line : summary.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("File:")) {
                return trimmed.substring(5).trim();
            }
        }
        return null;
    }

    static class MockLLMProvider extends LLMProvider {
        private final String summaryResponse;

        MockLLMProvider(String summaryResponse) {
            super(new LLMProviderConfig("test-model", 0.7, null));
            this.summaryResponse = summaryResponse;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            if (summaryResponse != null) {
                var response = new CompletionResponse();
                response.choices = List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, summaryResponse)));
                response.usage = new Usage();
                return response;
            }
            return null;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
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
