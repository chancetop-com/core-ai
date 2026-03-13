package ai.core.tool.tools;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderLifecycleTest {
    private TodoReminderLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new TodoReminderLifecycle(3);
    }

    @Test
    void noReminderWhenTodosNeverCalled() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file"),
                toolResult("c1", "read_file", "content"),
                assistantWithToolCall("c2", "edit_file"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "shell_command"),
                toolResult("c3", "shell_command", "output")
        ));
        var request = buildRequest(messages);

        assertFalse(lifecycle.hasTodosInContext(request));
        lifecycle.beforeModel(request, null);

        assertFalse(messages.getLast().getTextContent().contains("system-reminder"));
    }

    @Test
    void noReminderWhenRecentlyCalledWriteTodos() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", "write_todos"),
                toolResult("c1", "write_todos", "todos updated"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content")
        ));
        var request = buildRequest(messages);

        assertTrue(lifecycle.hasTodosInContext(request));
        assertEquals(1, lifecycle.countToolCallsSinceLastWriteTodos(request));

        lifecycle.beforeModel(request, null);
        assertFalse(messages.getLast().getTextContent().contains("system-reminder"));
    }

    @Test
    void reminderTriggeredAfterThresholdExceeded() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", "write_todos"),
                toolResult("c1", "write_todos", "todos updated"),
                Message.of(RoleType.ASSISTANT, "Working on it"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content1"),
                assistantWithToolCall("c3", "edit_file"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "shell_command"),
                toolResult("c4", "shell_command", "output")
        ));
        var request = buildRequest(messages);

        assertEquals(3, lifecycle.countToolCallsSinceLastWriteTodos(request));

        lifecycle.beforeModel(request, null);
        assertTrue(messages.getLast().getTextContent().contains("system-reminder"));
        assertTrue(messages.getLast().getTextContent().contains("write_todos"));
    }

    @Test
    void reminderResetsAfterWriteTodosCalledAgain() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", "write_todos"),
                toolResult("c1", "write_todos", "todos created"),
                Message.of(RoleType.ASSISTANT, "Working"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content"),
                assistantWithToolCall("c3", "edit_file"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "shell_command"),
                toolResult("c4", "shell_command", "output"),
                Message.of(RoleType.ASSISTANT, "Continuing"),
                assistantWithToolCall("c5", "write_todos"),
                toolResult("c5", "write_todos", "todos updated"),
                Message.of(RoleType.ASSISTANT, "Next step"),
                assistantWithToolCall("c6", "read_file"),
                toolResult("c6", "read_file", "more content")
        ));
        var request = buildRequest(messages);

        assertEquals(1, lifecycle.countToolCallsSinceLastWriteTodos(request));

        lifecycle.beforeModel(request, null);
        assertFalse(messages.getLast().getTextContent().contains("system-reminder"));
    }

    @Test
    void reminderNotDuplicated() {
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c1", "write_todos"),
                toolResult("c1", "write_todos", "todos updated"),
                Message.of(RoleType.ASSISTANT, "Working"),
                assistantWithToolCall("c2", "read_file"),
                toolResult("c2", "read_file", "content"),
                assistantWithToolCall("c3", "edit_file"),
                toolResult("c3", "edit_file", "done"),
                assistantWithToolCall("c4", "shell_command"),
                toolResult("c4", "shell_command", "output")
        ));
        var request = buildRequest(messages);

        lifecycle.beforeModel(request, null);
        assertTrue(messages.getLast().getTextContent().contains("system-reminder"));

        String contentAfterFirst = messages.getLast().getTextContent();

        lifecycle.beforeModel(request, null);
        assertEquals(contentAfterFirst, messages.getLast().getTextContent());
    }

    @Test
    void nullRequestHandledGracefully() {
        lifecycle.beforeModel(null, null);
        // no exception
    }

    @Test
    void emptyMessagesHandledGracefully() {
        var request = buildRequest(new ArrayList<>());
        lifecycle.beforeModel(request, null);
        // no exception
    }

    @Test
    void customThreshold() {
        var customLifecycle = new TodoReminderLifecycle(5);
        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "task"),
                assistantWithToolCall("c1", "write_todos"),
                toolResult("c1", "write_todos", "todos"),
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
        var request = buildRequest(messages);

        customLifecycle.beforeModel(request, null);
        assertFalse(messages.getLast().getTextContent().contains("system-reminder"));

        messages.add(assistantWithToolCall("c6", "t5"));
        messages.add(toolResult("c6", "t5", "r5"));

        customLifecycle.beforeModel(request, null);
        assertTrue(messages.getLast().getTextContent().contains("system-reminder"));
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
