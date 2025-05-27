package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class Message {
    public static Message of(RoleType role, String content) {
        var message = new Message();
        message.role = role;
        message.content = content == null ? "" : content;
        return message;
    }

    public static Message of(RoleType role, String content, String name) {
        var message = new Message();
        message.role = role;
        message.content = content == null ? "" : content;
        message.name = name;
        return message;
    }

    public static Message of(RoleType role, String content, String name, String toolCallId, FunctionCall functionCall, List<FunctionCall> toolCalls) {
        var message = new Message();
        message.role = role;
        message.content = content == null ? "" : content;
        message.name = name;
        message.toolCallId = toolCallId;
        message.functionCall = functionCall;
        message.toolCalls = toolCalls;
        return message;
    }

    @NotNull
    @Property(name = "role")
    public RoleType role;
    @NotNull
    @Property(name = "content")
    public String content;
    @Property(name = "name")
    public String name;
    @Property(name = "tool_call_id")
    public String toolCallId;
    @Property(name = "function_call")
    public FunctionCall functionCall;
    @Property(name = "tool_calls")
    public List<FunctionCall> toolCalls;

    private String agentName;
    private String groupName;

    public String getName() {
        return name;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
}
