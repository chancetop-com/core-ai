package ai.core.llm.providers.inner;

import ai.core.tool.ToolCall;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public String name;
    public Double temperature;
    public String model;
    public List<LLMMessage> messages;
    public List<ToolCall> toolCalls;

    public CompletionRequest(List<LLMMessage> messages, List<ToolCall> toolCalls, Double temperature, String model, String name) {
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.model = model;
        this.temperature = temperature;
        this.name = name;
    }
}
