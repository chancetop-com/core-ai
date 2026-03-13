package ai.core.agent.doomloop;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoomLoopLifecycleTest {

    @Test
    void nullRequestHandledGracefully() {
        var lifecycle = new DoomLoopLifecycle(List.of(new RepetitiveCallStrategy()));
        lifecycle.beforeModel(null, null);
    }

    @Test
    void emptyMessagesHandledGracefully() {
        var lifecycle = new DoomLoopLifecycle(List.of(new RepetitiveCallStrategy()));
        lifecycle.beforeModel(buildRequest(new ArrayList<>()), null);
    }

    @Test
    void warningAppendedToLastToolMessage() {
        var lifecycle = new DoomLoopLifecycle(List.of(new RepetitiveCallStrategy(4, 3)));

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c3", "edit_file", "done")
        ));
        var request = buildRequest(messages);

        lifecycle.beforeModel(request, null);

        var lastToolMsg = messages.getLast();
        assertTrue(lastToolMsg.getTextContent().contains("[SYSTEM WARNING]"));
        assertTrue(lastToolMsg.getTextContent().contains("doom loop"));
    }

    @Test
    void noWarningWhenNoPatternDetected() {
        var lifecycle = new DoomLoopLifecycle(List.of(new RepetitiveCallStrategy(4, 3)));

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "read_file", "{\"path\":\"/a.txt\"}"),
                toolResult("c1", "read_file", "content"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/b.txt\"}"),
                toolResult("c2", "edit_file", "done")
        ));
        var request = buildRequest(messages);

        lifecycle.beforeModel(request, null);

        assertFalse(messages.getLast().getTextContent().contains("[SYSTEM WARNING]"));
    }

    @Test
    void multipleStrategiesApplied() {
        var strategies = List.<DoomLoopStrategy>of(
                new RepetitiveCallStrategy(6, 3),
                new TodoReminderStrategy(3)
        );
        var lifecycle = new DoomLoopLifecycle(strategies);

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do task"),
                assistantWithToolCall("c0", "write_todos", "{}"),
                toolResult("c0", "write_todos", "todos created"),
                Message.of(RoleType.ASSISTANT, "Working"),
                assistantWithToolCall("c1", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c3", "edit_file", "done")
        ));
        var request = buildRequest(messages);

        lifecycle.beforeModel(request, null);

        var lastContent = messages.getLast().getTextContent();
        assertTrue(lastContent.contains("[SYSTEM WARNING]"));
        assertTrue(lastContent.contains("system-reminder"));
    }

    @Test
    void warningNotDuplicated() {
        var lifecycle = new DoomLoopLifecycle(List.of(new RepetitiveCallStrategy(4, 3)));

        var messages = new ArrayList<>(List.of(
                Message.of(RoleType.USER, "do something"),
                assistantWithToolCall("c1", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c1", "edit_file", "done"),
                assistantWithToolCall("c2", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c2", "edit_file", "done"),
                assistantWithToolCall("c3", "edit_file", "{\"path\":\"/a.txt\",\"content\":\"x\"}"),
                toolResult("c3", "edit_file", "done")
        ));
        var request = buildRequest(messages);

        lifecycle.beforeModel(request, null);
        String contentAfterFirst = messages.getLast().getTextContent();

        lifecycle.beforeModel(request, null);
        String contentAfterSecond = messages.getLast().getTextContent();

        // Warning should not be duplicated
        assertEquals(contentAfterFirst, contentAfterSecond);
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
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
