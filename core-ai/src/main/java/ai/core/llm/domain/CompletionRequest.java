package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public static CompletionRequest of(List<Message> messages, List<Tool> tools, Double temperature, String model, String name) {
        var request = new CompletionRequest();
        request.model = model;
        request.messages = messages;
        request.temperature = temperature;
        request.name = name;
        request.tools = tools;
        return request;
    }
    public static CompletionRequest of(CompletionRequestOptions options) {
        var request = new CompletionRequest();
        request.model = options.model;
        request.messages = options.messages;
        request.temperature = options.temperature;
        request.name = options.name;
        request.tools = options.tools;
        request.stream = options.stream;
        request.responseFormat = options.responseFormat;
        request.reasoningEffort = options.reasoningEffort;
        return request;
    }

    @Property(name = "model")
    public String model;
    @Property(name = "messages")
    public List<Message> messages;
    @Property(name = "temperature")
    public Double temperature;
    @Property(name = "tools")
    public List<Tool> tools;
    @Property(name = "tool_choice")
    public String toolChoice;
    @Property(name = "stream")
    public Boolean stream;
    @Property(name = "stream_options")
    public StreamOptions streamOptions;
    @Property(name = "response_format")
    public ResponseFormat responseFormat;
    @Property(name = "reasoning_effort")
    public ReasoningEffort reasoningEffort;

    private String name;
    //todo other params(advanced)

    public String getName() {
        return name;
    }

    public record CompletionRequestOptions(List<Message> messages,
                                           List<Tool> tools,
                                           Double temperature,
                                           String model,
                                           String name,
                                           Boolean stream,
                                           ResponseFormat responseFormat,
                                           ReasoningEffort reasoningEffort) {
    }
}
