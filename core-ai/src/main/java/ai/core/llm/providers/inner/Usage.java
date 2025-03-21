package ai.core.llm.providers.inner;

import ai.core.litellm.completion.UsageAJAXView;

/**
 * @author stephen
 */
public class Usage {
    private int promptTokens;
    // completionTokens the number of tokens used for completion, caption will be 0
    private int completionTokens;
    private int totalTokens;

    public Usage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Usage() {
    }

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
