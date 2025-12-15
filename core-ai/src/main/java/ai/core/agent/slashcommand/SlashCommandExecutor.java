package ai.core.agent.slashcommand;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public final class SlashCommandExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlashCommandExecutor.class);

    public static ExecutionResult execute(SlashCommandResult command, List<ToolCall> toolCalls, ExecutionContext context) {
        if (command.isNotValid()) {
            return createErrorResult(command.getOriginalQuery(), "Invalid slash command format");
        }

        var toolName = command.getToolName();
        var arguments = command.hasArguments() ? command.getArguments() : "{}";

        // Find the tool
        var toolOptional = toolCalls.stream()
                .filter(t -> t.getName().equalsIgnoreCase(toolName))
                .findFirst();

        if (toolOptional.isEmpty()) {
            toolOptional = toolCalls.stream()
                    .filter(t -> t.getName().endsWith(toolName))
                    .findFirst();
        }

        if (toolOptional.isEmpty()) {
            return createErrorResult(command.getOriginalQuery(), "Tool not found: " + toolName);
        }

        var tool = toolOptional.get();
        var toolCallId = generateToolCallId();

        LOGGER.info("Slash command executing tool: {} with arguments: {}", toolName, arguments);

        // Execute the tool
        ToolCallResult result;
        try {
            var startTime = System.currentTimeMillis();
            result = tool.execute(arguments, context);
            result.withToolName(tool.getName()).withDuration(System.currentTimeMillis() - startTime);
            LOGGER.info("Slash command tool {} completed in {}ms", tool.getName(), result.getDurationMs());
        } catch (Exception e) {
            LOGGER.error("Slash command tool execution failed: {}", e.getMessage(), e);
            return createErrorResult(command.getOriginalQuery(), "Tool execution failed: " + e.getMessage());
        }

        // Build the 3 messages
        var messages = buildMessages(command.getOriginalQuery(), toolName, arguments, toolCallId, result);

        return ExecutionResult.success(messages, result.toResultForLLM(), result);
    }

    private static List<Message> buildMessages(String originalQuery, String toolName, String arguments, String toolCallId, ToolCallResult result) {
        // 1. USER message - the original slash command query
        var userMessage = Message.of(RoleType.USER, originalQuery, "user", null, null, null);

        // 2. ASSISTANT message - fake tool call message
        var functionCall = FunctionCall.of(toolCallId, "function", toolName, arguments);
        var assistantMessage = Message.of(RoleType.ASSISTANT, "", "assistant", null, null, List.of(functionCall));

        // 3. TOOL message - tool execution result
        var toolMessage = Message.of(RoleType.TOOL, result.toResultForLLM(), toolName, toolCallId, null, null);

        return List.of(userMessage, assistantMessage, toolMessage);
    }

    private static ExecutionResult createErrorResult(String originalQuery, String error) {
        var toolCallId = generateToolCallId();
        var toolName = "slash_command_error";

        // 1. USER message
        var userMessage = Message.of(RoleType.USER, originalQuery, "user", null, null, null);

        // 2. ASSISTANT message - fake error tool call
        var functionCall = FunctionCall.of(toolCallId, "function", toolName, "{}");
        var assistantMessage = Message.of(RoleType.ASSISTANT, "", "assistant", null, null, List.of(functionCall));

        // 3. TOOL message - error result
        var toolMessage = Message.of(RoleType.TOOL, "Error: " + error, toolName, toolCallId, null, null);

        return ExecutionResult.failure(List.of(userMessage, assistantMessage, toolMessage), error);
    }

    private static String generateToolCallId() {
        return "slash_command_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private SlashCommandExecutor() {
    }

    public static final class ExecutionResult {
        public static ExecutionResult success(List<Message> messages, String toolResult, ToolCallResult rawResult) {
            return new ExecutionResult(messages, toolResult, rawResult, true);
        }

        public static ExecutionResult failure(List<Message> messages, String error) {
            return new ExecutionResult(messages, error, null, false);
        }

        private final List<Message> messages;
        private final String toolResult;
        private final ToolCallResult rawResult;
        private final boolean success;

        private ExecutionResult(List<Message> messages, String toolResult, ToolCallResult rawResult, boolean success) {
            this.messages = messages;
            this.toolResult = toolResult;
            this.rawResult = rawResult;
            this.success = success;
        }

        public List<Message> getMessages() {
            return messages;
        }

        public String getToolResult() {
            return toolResult;
        }

        public ToolCallResult getRawResult() {
            return rawResult;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
