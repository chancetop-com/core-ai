package ai.core.context;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.LearnedContextWindows;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.LLMStreamException;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CompressionLifecycleTest {

    @Test
    void abortTheRunWhenTheAgentReplaysCallsAfterCompression() {
        var provider = new CompressionTest.MockLLMProvider("summary of the previous work");
        var compression = new Compression(
                new CompressionConfig(Boolean.TRUE, 0.8, 1, 100, 1000, null), provider, "test-model");
        var lifecycle = new CompressionLifecycle(compression);
        lifecycle.beforeAgentRun(new AtomicReference<>("review the document"), null);

        var call = FunctionCall.of("call-1", "function", "read_file",
                "{\"file_path\":\"doc.md\",\"offset\":1,\"limit\":300}");

        lifecycle.afterTool(call, null, ToolCallResult.completed("content"));
        lifecycle.beforeModel(request(oversizedMessages()), null);

        for (int i = 0; i < CompressionThrashGuard.DEFAULT_REDUNDANT_CALL_THRESHOLD; i++) {
            lifecycle.afterTool(call, null, ToolCallResult.completed("content"));
        }

        var error = assertThrows(CompressionThrashException.class,
                () -> lifecycle.beforeModel(request(oversizedMessages()), null));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("read_file"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("doc.md"));
    }

    @Test
    void aNewRunStartsWithCleanThrashEvidence() {
        var provider = new CompressionTest.MockLLMProvider("summary of the previous work");
        var compression = new Compression(
                new CompressionConfig(Boolean.TRUE, 0.8, 1, 100, 1000, null), provider, "test-model");
        var lifecycle = new CompressionLifecycle(compression);
        var call = FunctionCall.of("call-1", "function", "read_file", "{\"file_path\":\"doc.md\"}");

        lifecycle.beforeAgentRun(new AtomicReference<>("first"), null);
        lifecycle.afterTool(call, null, ToolCallResult.completed("content"));
        lifecycle.beforeModel(request(oversizedMessages()), null);
        for (int i = 0; i < CompressionThrashGuard.DEFAULT_REDUNDANT_CALL_THRESHOLD; i++) {
            lifecycle.afterTool(call, null, ToolCallResult.completed("content"));
        }

        lifecycle.beforeAgentRun(new AtomicReference<>("second"), null);

        assertDoesNotThrow(() -> lifecycle.beforeModel(request(oversizedMessages()), null));
    }

    @Test
    void aSuccessfulWriteBreaksTheReplayEvidence() {
        var provider = new CompressionTest.MockLLMProvider("summary of the previous work");
        var compression = new Compression(
                new CompressionConfig(Boolean.TRUE, 0.8, 1, 100, 1000, null), provider, "test-model");
        var lifecycle = new CompressionLifecycle(compression);
        lifecycle.beforeAgentRun(new AtomicReference<>("implement"), null);
        var read = FunctionCall.of("call-read", "function", "read_file", "{\"file_path\":\"doc.md\"}");
        var write = FunctionCall.of("call-write", "function", "write_file", "{\"file_path\":\"doc.md\"}");

        lifecycle.afterTool(read, null, ToolCallResult.completed("content"));
        lifecycle.beforeModel(request(oversizedMessages()), null);
        lifecycle.afterTool(write, null, ToolCallResult.completed("ok"));
        for (int i = 0; i < CompressionThrashGuard.DEFAULT_REDUNDANT_CALL_THRESHOLD; i++) {
            lifecycle.afterTool(read, null, ToolCallResult.completed("content"));
        }

        assertDoesNotThrow(() -> lifecycle.beforeModel(request(oversizedMessages()), null));
    }

    @Test
    void compressesAndLearnsWhenTheProviderRejectsTheContextWindow() {
        var provider = new CompressionTest.MockLLMProvider("summary of the previous work");
        var compression = new Compression(
                new CompressionConfig(Boolean.TRUE, 0.8, 1, 100, 1000, null), provider, "overflow-test-model");
        var lifecycle = new CompressionLifecycle(compression);
        lifecycle.beforeAgentRun(new AtomicReference<>("implement"), null);
        var messages = oversizedMessages();
        var request = request(messages);
        request.model = "overflow-test-model";
        int before = request.messages.size();
        try {
            boolean recovered = lifecycle.onModelError(request, new LLMStreamException(
                    "This model's maximum context length is 4000 tokens. However, you requested 5000 tokens"), null);

            assertTrue(recovered);
            assertTrue(request.messages.size() < before);
            assertEquals(4000, LLMModelContextRegistry.getInstance().contextWindow("overflow-test-model"));
        } finally {
            LearnedContextWindows.clear();
        }
    }

    @Test
    void unrelatedModelErrorsAreNotClaimed() {
        var provider = new CompressionTest.MockLLMProvider("summary");
        var compression = new Compression(
                new CompressionConfig(Boolean.TRUE, 0.8, 1, 100, 1000, null), provider, "test-model");
        var lifecycle = new CompressionLifecycle(compression);
        lifecycle.beforeAgentRun(new AtomicReference<>("implement"), null);
        var request = request(oversizedMessages());
        int before = request.messages.size();

        assertFalse(lifecycle.onModelError(request, new LLMStreamException("invalid tool schema"), null));
        assertEquals(before, request.messages.size());
    }

    private CompletionRequest request(List<Message> messages) {
        var request = new CompletionRequest();
        request.messages = messages;
        return request;
    }

    private List<Message> oversizedMessages() {
        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.SYSTEM, "system prompt"));
        for (int i = 0; i < 4; i++) {
            messages.add(Message.of(RoleType.USER, "user note ".repeat(200) + i));
            messages.add(Message.of(RoleType.ASSISTANT, "assistant note ".repeat(200) + i));
        }
        return messages;
    }
}
