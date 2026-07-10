package ai.core.agent;

import ai.core.agent.atmention.AtMentionParser;
import ai.core.agent.atmention.AtMentionResult;
import ai.core.agent.internal.AgentHelper;
import ai.core.agent.slashcommand.SlashCommandParser;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;

import java.util.List;
import java.util.function.BiFunction;

/**
 * @author stephen
 */
final class SyntheticMessageFactory {

    static BiFunction<List<Message>, List<Tool>, Choice> wrapFirstTurn(Agent agent, BiFunction<List<Message>, List<Tool>, Choice> firstTurnBuilder) {
        return (messages, tools) -> {
            if (RoleType.TOOL.equals(messages.getLast().role)) {
                return ModelGateway.handLLM(agent, messages, tools);
            }
            return firstTurnBuilder.apply(messages, tools);
        };
    }

    static Choice constructionFakeAtMentionAssistantMsg(Agent agent, List<Message> messages, List<Tool> tools) {
        String query = messages.getLast().getTextContent();
        var registry = agent.getExecutionContext().getAgentProfileRegistry();
        var result = AtMentionParser.parse(query, registry);
        if (result.isEmpty()) {
            return Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT,
                    "Error: Unknown agent. Use /agents to list available agents.", "assistant", null, null, ""));
        }
        AtMentionResult mention = result.get();
        String taskArgs = "{\"subagent_type\":\"" + mention.agentName()
                + "\",\"prompt\":\"" + escapeJson(mention.prompt())
                + "\",\"description\":\"" + escapeJson(truncateDescription(mention.prompt())) + "\"}";
        var functionCall = FunctionCall.of(AgentHelper.generateToolCallId(), "function", "task", taskArgs);
        return Choice.of(FinishReason.TOOL_CALLS, Message.of(RoleType.ASSISTANT, "", "assistant", null, List.of(functionCall), ""));
    }

    static Choice constructionFakeSlashCommandAssistantMsg(Agent agent, List<Message> messages, List<Tool> tools) {
        agent.logger.debug("all tools size is {}", tools.size());
        String query = messages.getLast().getTextContent();
        var command = SlashCommandParser.parse(query);
        if (command.isNotValid()) {
            return Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Error: Invalid slash command format. Expected: /slash_command:tool_name:arguments", "assistant", null, null, ""));
        } else {
            var functionCall = FunctionCall.of(AgentHelper.generateToolCallId(), "function", command.getToolName(), command.hasArguments() ? command.getArguments() : "{}");
            return Choice.of(FinishReason.TOOL_CALLS, Message.of(RoleType.ASSISTANT, "", "assistant", null, List.of(functionCall), ""));
        }
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String truncateDescription(String prompt) {
        return prompt.length() > 50 ? prompt.substring(0, 47) + "..." : prompt;
    }

    private SyntheticMessageFactory() {
    }
}
