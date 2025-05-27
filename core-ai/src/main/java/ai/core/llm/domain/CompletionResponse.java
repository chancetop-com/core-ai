package ai.core.llm.domain;

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
}
