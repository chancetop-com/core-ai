package ai.core.llm.providers.inner;

import ai.core.tool.function.Function;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public Double temperature;
    public List<Message> messages;
    public List<Function> functions;

    public CompletionRequest(List<Message> messages, List<Function> functions, Double temperature) {
        this.messages = messages;
        this.functions = functions;
        this.temperature = temperature == null ? Double.valueOf(0.7) : temperature;
    }
}
