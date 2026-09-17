package ai.core.context;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.streaming.StreamingCallback;
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
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.sandbox.Sandbox;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
        assertEquals(0.8, defaultCompression.getTriggerThreshold(), 0.0001);
        assertEquals(5, defaultCompression.getKeepRecentTurns());
        LOGGER.info("Default constructor test passed");
    }

    @Test
    void testCustomConstructor() {
        Compression customCompression = new Compression(0.8, 3, createMockProvider(), "test-model", "test-model");
        assertEquals(0.8, customCompression.getTriggerThreshold(), 0.0001);
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

        List<Message> messages = new ArrayList<>(22);
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

        List<Message> messages = new ArrayList<>(12);
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

    @Test
    void testToolCallsAndToolResponsePairPreserved() {
        // test that tool_calls and their corresponding tool responses are kept together
        Compression testCompression = new Compression(0.0001, 1, 100,
            createMockProviderWithSummary("Summary"), "test-model", "test-model");

        List<Message> messages = new ArrayList<>(14);
        messages.add(Message.of(RoleType.SYSTEM, "System prompt"));

        // add some early messages that will be compressed
        for (int i = 0; i < 5; i++) {
            messages.add(Message.of(RoleType.USER, "User " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Assistant " + i));
        }

        // add ASSISTANT with tool_calls
        String toolCallId = "call_123";
        FunctionCall toolCall = FunctionCall.of(toolCallId, "function", "search", "{\"query\":\"test\"}");
        Message assistantWithToolCall = Message.of(RoleType.ASSISTANT, null, null, null, List.of(toolCall));
        messages.add(assistantWithToolCall);

        // add TOOL response
        Message toolResponse = Message.of(RoleType.TOOL, "Search result", "search", toolCallId, null);
        messages.add(toolResponse);

        // add final USER message
        messages.add(Message.of(RoleType.USER, "Final user message"));

        List<Message> result = testCompression.compress(messages);

        // verify that we have valid message structure: every TOOL has matching ASSISTANT
        verifyToolPairsIntact(result);

        LOGGER.info("Tool calls and tool response pair preserved test passed: {} -> {} messages", messages.size(), result.size());
    }

    @Test
    void testDropOrphanToolMessages() {
        // simulate the failure case: compression keeps a tool message whose assistant.tool_calls was dropped
        String pairedCallId = "call_paired";
        FunctionCall pairedCall = FunctionCall.of(pairedCallId, "function", "echo", "{}");

        List<Message> messages = List.of(
            Message.of(RoleType.SYSTEM, "system"),
            // orphan tool: no preceding assistant declared call_orphan
            Message.of(RoleType.TOOL, "orphan binary blob", "read_file", "call_orphan", null),
            Message.of(RoleType.USER, "follow-up user message"),
            Message.of(RoleType.ASSISTANT, null, null, null, List.of(pairedCall)),
            Message.of(RoleType.TOOL, "echo result", "echo", pairedCallId, null)
        );

        List<Message> sanitized = ToolCallPruning.dropOrphanToolMessages(messages);

        // orphan should be dropped, paired tool/assistant retained
        assertEquals(4, sanitized.size());
        assertEquals(RoleType.SYSTEM, sanitized.get(0).role);
        assertEquals(RoleType.USER, sanitized.get(1).role);
        assertEquals(RoleType.ASSISTANT, sanitized.get(2).role);
        assertEquals(RoleType.TOOL, sanitized.get(3).role);
        assertEquals(pairedCallId, sanitized.get(3).toolCallId);

        verifyToolPairsIntact(sanitized);
        LOGGER.info("Drop orphan tool messages test passed: {} -> {} messages", messages.size(), sanitized.size());
    }

    @Test
    void testCompressKeepsToolResultTogetherWithItsAssistant() {
        // the conversation tail ends with a tool result: if the kept slice started at that result, the
        // assistant declaring the call would be summarized away and the result itself dropped as an orphan
        Compression testCompression = new Compression(0.0001, 1, 100,
            createMockProviderWithSummary("Summary"), "test-model", "test-model");

        String longResult = "detail ".repeat(2000);
        String toolCallId = "call_tail";
        FunctionCall toolCall = FunctionCall.of(toolCallId, "function", "read", "{}");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(RoleType.SYSTEM, "System prompt"));
        for (int i = 0; i < 5; i++) {
            messages.add(Message.of(RoleType.USER, "User " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Assistant " + i));
        }
        messages.add(Message.of(RoleType.USER, "Final user message"));
        messages.add(Message.of(RoleType.ASSISTANT, null, null, null, List.of(toolCall)));
        messages.add(Message.of(RoleType.TOOL, longResult, "read", toolCallId, null));

        List<Message> result = testCompression.compress(messages);

        verifyToolPairsIntact(result);
        Message keptToolResult = result.stream()
            .filter(m -> m.role == RoleType.TOOL && toolCallId.equals(m.toolCallId))
            .findFirst()
            .orElse(null);
        assertNotNull(keptToolResult, "tool result must not be dropped by compression");
        assertEquals(longResult, keptToolResult.getTextContent());

        LOGGER.info("Kept tool result with its assistant test passed: {} -> {} messages", messages.size(), result.size());
    }

    @Test
    void testUpdateModelRepointsContextWindow() {
        Compression target = new Compression(createMockProvider(), "unknown-model-xyz");
        assertEquals(128000, target.getMaxContextTokens());

        var modelInfo = LLMModelContextRegistry.getInstance().getModelInfo("gpt-3.5-turbo");
        assertNotNull(modelInfo, "test model must be present in the context registry");
        target.updateModel(createMockProviderWithSummary("Summary"), "gpt-3.5-turbo");

        assertEquals(modelInfo.contextWindow(), target.getMaxContextTokens());
        assertEquals(modelInfo.contextWindow() / 2, target.getMaxToolResultTokens());
        assertTrue(target.shouldCompress(modelInfo.contextWindow()));
        assertFalse(target.shouldCompress(modelInfo.contextWindow() / 5));

        LOGGER.info("Update model repoints context window test passed");
    }

    @Test
    void testSummaryCallUsageIsReported() {
        var usage = new Usage(1200, 300, 1500);
        var provider = new MockLLMProvider("Compressed summary", usage);
        Compression target = new Compression(provider, "test-model");
        var reported = new ArrayList<String>();
        target.onLlmUsage((model, reportedUsage) -> reported.add(model + ":" + reportedUsage.getTotalTokens()));

        var messages = createTestMessages(10);
        var result = target.forceCompress(messages);

        assertNotEquals(messages, result);
        assertEquals(List.of("test-model:1500"), reported);
        assertNull(target.getLastFailure());

        LOGGER.info("Summary call usage reported test passed");
    }

    @Test
    void testLastFailureExplainsEmptySummary() {
        Compression target = new Compression(new MockLLMProvider(""), "test-model");
        var messages = createTestMessages(10);

        var result = target.forceCompress(messages);

        assertEquals(messages, result);
        assertEquals("summarization returned an empty result", target.getLastFailure());

        LOGGER.info("Last failure explains empty summary test passed");
    }

    @Test
    void testSkippedCompressionIsReportedAfterItStarts() {
        Compression target = new Compression(new MockLLMProvider(""), "test-model");
        var events = new ArrayList<String>();
        target.addListener(new CompressionListener() {
            @Override
            public void onCompression(int beforeCount, int afterCount, boolean completed) {
                events.add((completed ? "completed " : "started ") + beforeCount + ":" + afterCount);
            }

            @Override
            public void onCompressionSkipped(int beforeCount, String reason) {
                events.add("skipped " + beforeCount + ":" + reason);
            }
        });

        var messages = createTestMessages(10);
        target.forceCompress(messages);

        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(0).startsWith("started "), events.toString());
        assertEquals("skipped " + messages.size() + ":summarization returned an empty result", events.get(1));

        LOGGER.info("Skipped compression reported test passed");
    }

    @Test
    void testCompressionConfigOverridesDefaults() {
        var provider = new MockLLMProvider("Summary", new Usage(10, 5, 15));
        var config = new CompressionConfig(true, 0.5, 2, 1000, 32000, "cheap-summary-model");
        Compression target = new Compression(config, provider, "unknown-model-xyz");

        assertEquals(32000, target.getMaxContextTokens());
        assertEquals(16000, target.getMaxToolResultTokens());
        assertEquals(0.5, target.getTriggerThreshold(), 0.0001);
        assertEquals(2, target.getKeepRecentTurns());
        assertFalse(target.shouldCompress(15000));
        assertTrue(target.shouldCompress(16000));

        target.forceCompress(createTestMessages(10));

        assertEquals("cheap-summary-model", provider.getLastRequestModel());

        LOGGER.info("Compression config overrides defaults test passed");
    }

    private void verifyToolPairsIntact(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.role != RoleType.TOOL || msg.toolCallId == null) {
                continue;
            }
            boolean found = hasMatchingAssistant(messages, i, msg.toolCallId);
            assertTrue(found, "TOOL message with toolCallId=" + msg.toolCallId + " should have preceding ASSISTANT");
        }
    }

    private boolean hasMatchingAssistant(List<Message> messages, int toolIndex, String toolCallId) {
        for (int j = toolIndex - 1; j >= 0; j--) {
            Message prevMsg = messages.get(j);
            if (prevMsg.role != RoleType.ASSISTANT || prevMsg.toolCalls == null) {
                continue;
            }
            boolean matches = prevMsg.toolCalls.stream().anyMatch(tc -> tc.id != null && tc.id.equals(toolCallId));
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private List<Message> createTestMessages(int turns) {
        List<Message> messages = new ArrayList<>(turns * 2 + 1);
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
        // Create content that exceeds the per-model tool result token limit
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

    @Test
    void testCompressToolResultSpillsIntoTheSandbox() {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        Sandbox sandbox = mock(Sandbox.class);
        List<String> uploaded = new ArrayList<>();
        doAnswer(invocation -> {
            uploaded.add(invocation.getArgument(0));
            return null;
        }).when(sandbox).uploadFile(anyString(), any(byte[].class));

        String compressed = compressionWithProvider.compressToolResult("run_bash_command", "word ".repeat(100000), "session-9", sandbox);

        assertEquals(1, uploaded.size());
        String sandboxPath = uploaded.getFirst();
        assertTrue(sandboxPath.startsWith("/tmp/core-ai/session-9/"));
        assertTrue(compressed.contains(sandboxPath));
        assertFalse(Files.exists(localSpillPath(sandboxPath)), "a result spilled into the sandbox must not also be written to the local temp directory");
    }

    private Path localSpillPath(String sandboxPath) {
        return Path.of(System.getProperty("java.io.tmpdir"), "core-ai", "session-9", Path.of(sandboxPath).getFileName().toString());
    }

    @Test
    void testCompressToolResultWithoutStorageStillTruncates() throws Exception {
        Compression compressionWithProvider = new Compression(createMockProvider(), "test-model");
        Sandbox sandbox = mock(Sandbox.class);
        doThrow(new IllegalStateException("sandbox is down")).when(sandbox).uploadFile(anyString(), any(byte[].class));

        String compressed = compressionWithProvider.compressToolResult("run_bash_command", "word ".repeat(100000), "session-9", sandbox);

        assertFalse(compressed.contains("File:"));
        assertTrue(compressed.contains("Re-run the tool with a narrower"));
        assertTrue(compressed.contains("HEAD (first 500 tokens)"));
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
        private final Usage usage;
        private String lastRequestModel;

        MockLLMProvider(String summaryResponse) {
            this(summaryResponse, null);
        }

        MockLLMProvider(String summaryResponse, Usage usage) {
            super(new LLMProviderConfig("test-model", 0.7, null));
            this.summaryResponse = summaryResponse;
            this.usage = usage;
        }

        String getLastRequestModel() {
            return lastRequestModel;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            lastRequestModel = request.model;
            if (summaryResponse != null) {
                var response = new CompletionResponse();
                response.choices = List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, summaryResponse)));
                response.usage = usage != null ? usage : new Usage();
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
