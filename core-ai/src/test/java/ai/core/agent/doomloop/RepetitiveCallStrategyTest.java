package ai.core.agent.doomloop;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepetitiveCallStrategyTest {
    private RepetitiveCallStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RepetitiveCallStrategy(6, 3);
    }

    @Test
    void noDetectionWhenBelowThreshold() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c1", "read_file", "content"),
                assistantWithToolCall("c2", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c2", "read_file", "content")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
    }

    @Test
    void detectConsecutiveRepeat() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c3", "edit_file", "done")
        ));

        assertTrue(strategy.detect(buildRequest(messages), null));
        assertTrue(strategy.warningMessage().contains("doom loop"));
        assertTrue(strategy.warningMessage().contains("edit_file"));
        assertTrue(strategy.warningMessage().contains("3 times consecutively"));
    }

    @Test
    void noFalsePositiveWithDifferentArguments() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c1", "read_file", "content1"),
                assistantWithToolCall("c2", "read_file", "{\"path\":\"/b.txt\"}"),
                toolResult("c2", "read_file", "content2"),
                assistantWithToolCall("c3", "read_file", "{\"path\":\"/c.txt\"}"),
                toolResult("c3", "read_file", "content3")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
    }

    @Test
    void detectCyclicPattern() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c1", "read_file", "content"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c3", "read_file", "content"),
                assistantWithToolCall("c4", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c4", "edit_file", "done")
        ));

        assertTrue(strategy.detect(buildRequest(messages), null));
        assertTrue(strategy.warningMessage().contains("cyclic doom loop"));
        assertTrue(strategy.warningMessage().contains("pattern of length 2"));
    }

    @Test
    void recoveryAfterDiverseActions() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "edit_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "read_file", "{\"path\":\"/b.txt\"}"),
                toolResult("c3", "read_file", "content")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
    }

    @Test
    void windowSizeLimitsExtraction() {
        var smallStrategy = new RepetitiveCallStrategy(3, 3);

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "edit_file", "{\"a\":1}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "read_file", "{\"b\":2}"),
                toolResult("c2", "read_file", "content"),
                assistantWithToolCall("c3", "edit_file", "{\"a\":1}"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "edit_file", "{\"a\":1}"),
                toolResult("c4", "edit_file", "done")
        ));

        // Window of 3: sees [read_file, edit_file, edit_file] - only 2 consecutive
        assertFalse(smallStrategy.detect(buildRequest(messages), null));
    }

    @Test
    void emptyMessagesReturnsFalse() {
        assertFalse(strategy.detect(buildRequest(new ArrayList<>()), null));
    }

    private CompletionRequest buildRequest(List<Message> messages) {
        return CompletionRequest.of(messages, List.of(), 0.0, "test-model", "test");
    }

    private Message assistantWithToolCall(String callId, String toolName, String arguments) {
        FunctionCall call = FunctionCall.of(callId, "function", toolName, arguments);
        return Message.of(RoleType.ASSISTANT, null, null, null, List.of(call));
    }

    private Message toolResult(String callId, String toolName, String content) {
        return Message.of(RoleType.TOOL, content, toolName, callId, null);
    }
}
