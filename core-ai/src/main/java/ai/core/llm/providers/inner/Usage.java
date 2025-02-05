package ai.core.llm.providers.inner;

import ai.core.litellm.completion.UsageAJAXView;

/**
 * @author stephen
 */
public class Usage {
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    public Usage(UsageAJAXView usageAJAXView) {
        this.promptTokens = usageAJAXView.promptTokens;
        this.completionTokens = usageAJAXView.completionTokens;
        this.totalTokens = usageAJAXView.totalTokens;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
