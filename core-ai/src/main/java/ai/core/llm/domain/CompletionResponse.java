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
     * Provider-side latency from issuing the request to the first content/reasoning token, measured by
     * {@link ai.core.llm.LLMProvider}. It is runtime observability data, not part of the wire payload.
     */
    @JsonIgnore
    public Long ttftMs;
}
