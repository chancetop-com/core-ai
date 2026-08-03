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
        // NONE means "no reasoning effort" — omit the field so providers that do not accept "none" fall back to their default
        request.reasoningEffort = options.reasoningEffort == ReasoningEffort.NONE ? null : options.reasoningEffort;
        return request;
    }

    @Property(name = "model")
    public String model;
    @Property(name = "messages")
    public List<Message> messages;
    @Property(name = "temperature")
    public Double temperature;
    @Property(name = "top_p")
    public Double topP;
    @Property(name = "max_completion_tokens")
    public Integer maxCompletionTokens;
    @Property(name = "parallel_tool_calls")
    public Boolean parallelToolCalls;
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
    private Object extraBody;
    private Integer timeoutSeconds;
    // resolved per-model reasoning effort value (e.g. "xhigh" mapped from internal MAX),
    // takes precedence over the reasoningEffort enum when serializing to upstream
    private String reasoningEffortValue;

    public String getName() {
        return name;
    }
    public Object getExtraBody() {
        return extraBody;
    }
    public void setExtraBody(Object extraBody) {
        this.extraBody = extraBody;
    }
    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }
    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
    public String getReasoningEffortValue() {
        return reasoningEffortValue;
    }
    public void setReasoningEffortValue(String reasoningEffortValue) {
        this.reasoningEffortValue = reasoningEffortValue;
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
