package ai.core.llm.providers.inner;

import ai.core.agent.AgentRole;

import java.util.List;

/**
 * @author stephen
 */
public class Message {
    public static Message of(AgentRole role, String agentName, String content) {
        var message = new Message();
        message.role = role;
        message.content = content;
        message.name = agentName;
        return message;
    }

    public static Message of(AgentRole role, String content, String name, String toolCallId, FunctionCall functionCall, List<FunctionCall> toolCalls) {
        var message = new Message();
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
    public FunctionCall functionCall;
    public List<FunctionCall> toolCalls;
    public String agentName;
    public String groupName;
}
