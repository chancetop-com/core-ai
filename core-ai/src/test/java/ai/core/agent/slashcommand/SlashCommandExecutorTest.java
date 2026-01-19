package ai.core.agent.slashcommand;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.RoleType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SlashCommandExecutorTest {
    private List<ToolCall> toolCalls;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        toolCalls = List.of(
            createMockTool("echo_tool", "Echoes the input text"),
            createMockTool("add_numbers", "Adds two numbers"),
            createMockTool("namespace_prefix_tool", "A tool with namespace prefix")
        );
        context = ExecutionContext.builder()
            .sessionId("test-session")
            .userId("test-user")
            .build();
    }

    @Test
    void testExecuteWithValidCommand() {
        var command = SlashCommandResult.valid(
            "/slash_command:echo_tool:{\"text\": \"hello\"}",
            "echo_tool",
            "{\"text\": \"hello\"}"
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMessages());
        assertEquals(3, result.getMessages().size());

        // Verify USER message
        var userMsg = result.getMessages().getFirst();
        assertEquals(RoleType.USER, userMsg.role);
        assertEquals("/slash_command:echo_tool:{\"text\": \"hello\"}", userMsg.getTextContent());

        // Verify ASSISTANT message with toolCalls
        var assistantMsg = result.getMessages().get(1);
        assertEquals(RoleType.ASSISTANT, assistantMsg.role);
        assertNotNull(assistantMsg.toolCalls);
        assertEquals(1, assistantMsg.toolCalls.size());
        assertEquals("echo_tool", assistantMsg.toolCalls.getFirst().function.name);
        assertEquals("{\"text\": \"hello\"}", assistantMsg.toolCalls.getFirst().function.arguments);

        // Verify TOOL message
        var toolMsg = result.getMessages().get(2);
        assertEquals(RoleType.TOOL, toolMsg.role);
        assertTrue(toolMsg.getTextContent().contains("hello"));
        assertEquals(assistantMsg.toolCalls.getFirst().id, toolMsg.toolCallId);
    }

    @Test
    void testExecuteWithNoArguments() {
        var command = SlashCommandResult.valid(
            "/slash_command:echo_tool",
            "echo_tool",
            null
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getMessages().size());

        // Verify ASSISTANT message has empty arguments
        var assistantMsg = result.getMessages().get(1);
        assertEquals("{}", assistantMsg.toolCalls.getFirst().function.arguments);
    }

    @Test
    void testExecuteWithToolNotFound() {
        var command = SlashCommandResult.valid(
            "/slash_command:nonexistent_tool:{}",
            "nonexistent_tool",
            "{}"
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        assertFalse(result.isSuccess());
        assertEquals(3, result.getMessages().size());

        // Verify error in TOOL message
        var toolMsg = result.getMessages().get(2);
        assertEquals(RoleType.TOOL, toolMsg.role);
        assertTrue(toolMsg.getTextContent().contains("Tool not found"));
    }

    @Test
    void testExecuteWithInvalidCommand() {
        var command = SlashCommandResult.invalid("invalid query");

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        assertFalse(result.isSuccess());
        assertEquals(3, result.getMessages().size());

        var toolMsg = result.getMessages().get(2);
        assertTrue(toolMsg.getTextContent().contains("Invalid slash command format"));
    }

    @Test
    void testExecuteWithPartialToolNameMatch() {
        var command = SlashCommandResult.valid(
            "/slash_command:prefix_tool:{}",
            "prefix_tool",
            "{}"
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getMessages().size());

        // Should match namespace_prefix_tool by endsWith
        var assistantMsg = result.getMessages().get(1);
        assertEquals("prefix_tool", assistantMsg.toolCalls.getFirst().function.name);
    }

    @Test
    void testExecuteWithToolException() {
        var errorTool = createErrorTool();

        var command = SlashCommandResult.valid(
            "/slash_command:error_tool:{}",
            "error_tool",
            "{}"
        );

        var result = SlashCommandExecutor.execute(command, List.of(errorTool), context);

        assertFalse(result.isSuccess());
        assertEquals(3, result.getMessages().size());

        var toolMsg = result.getMessages().get(2);
        assertTrue(toolMsg.getTextContent().contains("Tool execution failed"));
    }

    @Test
    void testToolCallIdFormat() {
        var command = SlashCommandResult.valid(
            "/slash_command:echo_tool:{}",
            "echo_tool",
            "{}"
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        var assistantMsg = result.getMessages().get(1);
        var toolMsg = result.getMessages().get(2);

        assertNotNull(assistantMsg.toolCalls.getFirst().id);
        assertTrue(assistantMsg.toolCalls.getFirst().id.startsWith("slash_command_"));
        assertEquals(assistantMsg.toolCalls.getFirst().id, toolMsg.toolCallId);
    }

    @Test
    void testMessageSequenceIntegrity() {
        var command = SlashCommandResult.valid(
            "/slash_command:add_numbers:{\"a\": 1, \"b\": 2}",
            "add_numbers",
            "{\"a\": 1, \"b\": 2}"
        );

        var result = SlashCommandExecutor.execute(command, toolCalls, context);

        var messages = result.getMessages();
        assertEquals(3, messages.size());

        // Check message order: USER -> ASSISTANT -> TOOL
        assertEquals(RoleType.USER, messages.get(0).role);
        assertEquals(RoleType.ASSISTANT, messages.get(1).role);
        assertEquals(RoleType.TOOL, messages.get(2).role);

        // Check tool call ID chain
        var toolCallId = messages.get(1).toolCalls.getFirst().id;
        assertEquals(toolCallId, messages.get(2).toolCallId);
    }

    private ToolCall createMockTool(String name, String description) {
        return new MockToolCall(name, description);
    }

    private ToolCall createErrorTool() {
        return new ErrorToolCall();
    }

    static final class MockToolCall extends ToolCall {
        private final String toolName;

        MockToolCall(String name, String description) {
            this.toolName = name;
            setName(name);
            setDescription(description);
            setParameters(List.of());
            setNeedAuth(false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolCallResult execute(String arguments) {
            var args = JSON.fromJSON(Map.class, arguments);
            if ("echo_tool".equals(toolName)) {
                var text = args.getOrDefault("text", "no text");
                return ToolCallResult.completed("Echo: " + text);
            } else if ("add_numbers".equals(toolName)) {
                var a = args.get("a") instanceof Number ? ((Number) args.get("a")).intValue() : 0;
                var b = args.get("b") instanceof Number ? ((Number) args.get("b")).intValue() : 0;
                return ToolCallResult.completed("Result: " + (a + b));
            }
            return ToolCallResult.completed("Executed: " + toolName);
        }
    }

    static final class ErrorToolCall extends ToolCall {
        ErrorToolCall() {
            setName("error_tool");
            setDescription("A tool that throws an exception");
            setParameters(List.of());
            setNeedAuth(false);
        }

        @Override
        public ToolCallResult execute(String arguments) {
            throw new RuntimeException("Simulated error");
        }
    }
}
