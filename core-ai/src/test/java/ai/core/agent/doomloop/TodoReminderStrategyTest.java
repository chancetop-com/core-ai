package ai.core.agent.doomloop;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.tools.WriteTodosTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderStrategyTest {
    private TodoReminderStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TodoReminderStrategy(3);
    }

    @Test
    void noDetectionWhenTodosNeverCalled() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file"),
                toolResult("c1", "read_file", "content"),
                assistantWithToolCall("c2", "edit_file"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "shell_command"),
                toolResult("c3", "shell_command", "output")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
    }

    @Test
    void noDetectionWhenRecentlyCalledWriteTodos() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", WriteTodosTool.WT_TOOL_NAME),
                toolResult("c1", WriteTodosTool.WT_TOOL_NAME, "todos updated"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
        assertEquals(1, strategy.countToolCallsSinceLastWriteTodos(buildRequest(messages)));
    }

    @Test
    void detectionTriggeredAfterThreshold() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", WriteTodosTool.WT_TOOL_NAME),
                toolResult("c1", WriteTodosTool.WT_TOOL_NAME, "todos updated"),
                Message.of(RoleType.ASSISTANT, "Working on it"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content1"),
                assistantWithToolCall("c3", "edit_file"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "shell_command"),
                toolResult("c4", "shell_command", "output")
        ));

        assertTrue(strategy.detect(buildRequest(messages), null));
        assertTrue(strategy.warningMessage().contains("system-reminder"));
    }

    @Test
    void detectionResetsAfterWriteTodosCalledAgain() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", WriteTodosTool.WT_TOOL_NAME),
                toolResult("c1", WriteTodosTool.WT_TOOL_NAME, "todos created"),
                Message.of(RoleType.ASSISTANT, "Working"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content"),
                assistantWithToolCall("c3", "edit_file"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "shell_command"),
                toolResult("c4", "shell_command", "output"),
                Message.of(RoleType.ASSISTANT, "Continuing"),
                assistantWithToolCall("c5", WriteTodosTool.WT_TOOL_NAME),
                toolResult("c5", WriteTodosTool.WT_TOOL_NAME, "todos updated"),
                Message.of(RoleType.ASSISTANT, "Next step"),
                assistantWithToolCall("c6", "read_file"),
                toolResult("c6", "read_file", "more content")
        ));

        assertFalse(strategy.detect(buildRequest(messages), null));
    }

    @Test
    void customThreshold() {
        var customStrategy = new TodoReminderStrategy(5);
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "task"),
                assistantWithToolCall("c1", WriteTodosTool.WT_TOOL_NAME),
                toolResult("c1", WriteTodosTool.WT_TOOL_NAME, "todos"),
                Message.of(RoleType.ASSISTANT, "ok"),
                assistantWithToolCall("c2", "t1"),
                toolResult("c2", "t1", "r1"),
                assistantWithToolCall("c3", "t2"),
                toolResult("c3", "t2", "r2"),
                assistantWithToolCall("c4", "t3"),
                toolResult("c4", "t3", "r3"),
                assistantWithToolCall("c5", "t4"),
                toolResult("c5", "t4", "r4")
        ));

        assertFalse(customStrategy.detect(buildRequest(messages), null));

        messages.add(assistantWithToolCall("c6", "t5"));
        messages.add(toolResult("c6", "t5", "r5"));

        assertTrue(customStrategy.detect(buildRequest(messages), null));
    }

    private CompletionRequest buildRequest(List<Message> messages) {
        return CompletionRequest.of(messages, List.of(), 0.0, "test-model", "test");
    }

    private Message assistantWithToolCall(String callId, String toolName) {
        FunctionCall call = FunctionCall.of(callId, "function", toolName, "{}");
        return Message.of(RoleType.ASSISTANT, null, null, null, List.of(call));
    }

    private Message toolResult(String callId, String toolName, String content) {
        return Message.of(RoleType.TOOL, content, toolName, callId, null);
    }
}
