package ai.core.llm.providers.inner;

import ai.core.tool.ToolCall;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public Double temperature;
    public String model;
    public List<Message> messages;
    public List<ToolCall> toolCalls;

    public CompletionRequest(List<Message> messages, List<ToolCall> toolCalls, Double temperature, String model) {
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.model = model;
        this.temperature = temperature;
    }
}
