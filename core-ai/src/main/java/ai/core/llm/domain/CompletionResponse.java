package ai.core.llm.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionResponse {
    public static CompletionResponse of(List<Choice> choices, Usage usage) {
        var response = new CompletionResponse();
        response.choices = choices;
        response.usage = usage;
        return response;
    }

    @Property(name = "choices")
    public List<Choice> choices;
    @Property(name = "usage")
    public Usage usage;
    /**
     * Model the provider reports as having produced this response. It is the upstream name, so it
     * differs from the requested alias when the gateway failed over to another model.
     */
    @Property(name = "model")
    public String model;
    /**
     * Provider-side latency from issuing the request to the first content/reasoning token, measured by
     * {@link ai.core.llm.LLMProvider}. It is runtime observability data, not part of the wire payload.
     */
    @JsonIgnore
    public Long ttftMs;

    /**
     * Provider-side latency to the first answer-content token. The gap between {@link #ttftMs} and this
     * value is the thinking time of a reasoning model; only tokens produced after this point belong to
     * the answer's decode window. Runtime observability data, not part of the wire payload.
     */
    @JsonIgnore
    public Long firstContentMs;
}
