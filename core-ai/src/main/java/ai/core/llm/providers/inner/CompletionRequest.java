package ai.core.llm.providers.inner;

import ai.core.tool.function.Function;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public Double temperature;
    public String model;
    public List<Message> messages;
    public List<Function> functions;

    public CompletionRequest(List<Message> messages, List<Function> functions, Double temperature, String model) {
        this.messages = messages;
        this.functions = functions;
        this.model = model;
        this.temperature = temperature;
    }
}
