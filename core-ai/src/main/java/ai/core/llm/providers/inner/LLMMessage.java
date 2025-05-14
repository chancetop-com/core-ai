package ai.core.llm.providers.inner;

import ai.core.agent.AgentRole;

import java.util.List;

/**
 * @author stephen
 */
public class LLMMessage {
    public static LLMMessage of(AgentRole role, String content) {
        var message = new LLMMessage();
        message.role = role;
        message.content = content;
        return message;
    }

    public static LLMMessage of(AgentRole role, String agentName, String content) {
        var message = new LLMMessage();
        message.role = role;
        message.content = content;
        message.name = agentName;
        return message;
    }

    public static LLMMessage of(AgentRole role, String content, String name, String toolCallId, LLMFunction.FunctionCall functionCall, List<LLMFunction.FunctionCall> toolCalls) {
        var message = new LLMMessage();
        message.role = role;
        message.content = content;
        message.name = name;
        message.toolCallId = toolCallId;
        message.functionCall = functionCall;
        message.toolCalls = toolCalls;
        return message;
    }

    public AgentRole role;
    public String content;
    public String name;
    public String toolCallId;
    public LLMFunction.FunctionCall functionCall;
    public List<LLMFunction.FunctionCall> toolCalls;
    public String agentName;
    public String groupName;
}
