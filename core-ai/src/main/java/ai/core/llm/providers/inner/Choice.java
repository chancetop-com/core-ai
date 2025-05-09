package ai.core.llm.providers.inner;

/**
 * @author stephen
 */
public class Choice {
    public FinishReason finishReason;
    public LLMMessage message;

    public Choice(FinishReason finishReason, LLMMessage message) {
        this.finishReason = finishReason;
        this.message = message;
    }
}
