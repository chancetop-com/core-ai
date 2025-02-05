package ai.core.llm.providers.inner;

/**
 * @author stephen
 */
public class Choice {
    public FinishReason finishReason;
    public Message message;

    public Choice(FinishReason finishReason, Message message) {
        this.finishReason = finishReason;
        this.message = message;
    }
}
