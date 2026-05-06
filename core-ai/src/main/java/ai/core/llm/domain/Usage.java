package ai.core.llm.domain;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Usage {
    @Property(name = "prompt_tokens")
    private int promptTokens;
    @Property(name = "completion_tokens")
    // completionTokens the number of tokens used for completion, caption will be 0
    private int completionTokens;
    @Property(name = "total_tokens")
    private int totalTokens;
    @Property(name = "completion_tokens_details")
    private CompletionTokensDetails completionTokensDetails;
    @Property(name = "prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;


    public Usage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Usage() {
    }

    @Override
    public String toString() {
        String reasoningDetails = null;
        String promptDetails = null;
        if (completionTokensDetails != null) {
            reasoningDetails = String.valueOf(completionTokensDetails.reasoningTokens);
        }
        if (promptTokensDetails != null) {
            promptDetails = String.valueOf(promptTokensDetails.cachedTokens);
        }
        return "Usage{"
                + "promptTokens=" + promptTokens
                + ", completionTokens=" + completionTokens
                + ", completionTokensDetails=" + reasoningDetails
                + ", promptTokensDetails=" + promptDetails
                + ", totalTokens=" + totalTokens
                + '}';
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

    public PromptTokensDetails getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }
    public void setPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public void add(Usage usage) {
        this.promptTokens += usage.promptTokens;
        this.completionTokens += usage.completionTokens;
        this.totalTokens += usage.totalTokens;
        if (usage.promptTokensDetails != null) {
            if (this.promptTokensDetails == null) {
                this.promptTokensDetails = new PromptTokensDetails();
            }
            this.promptTokensDetails.cachedTokens += usage.promptTokensDetails.cachedTokens;
        }
        if (usage.completionTokensDetails != null) {
            if (this.completionTokensDetails == null) {
                this.completionTokensDetails = new CompletionTokensDetails();
            }
            this.completionTokensDetails.reasoningTokens += usage.completionTokensDetails.reasoningTokens;
        }
    }

    public static class CompletionTokensDetails {
        @Property(name = "reasoning_tokens")
        public int reasoningTokens;
    }

    public static class PromptTokensDetails {
        @Property(name = "cached_tokens")
        public int cachedTokens;
    }
}
