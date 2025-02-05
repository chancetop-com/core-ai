package ai.core.llm.providers.inner;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionResponse {
    public List<Choice> choices;
    public Usage usage;

    public CompletionResponse(List<Choice> choices, Usage usage) {
        this.choices = choices;
        this.usage = usage;
    }
}
