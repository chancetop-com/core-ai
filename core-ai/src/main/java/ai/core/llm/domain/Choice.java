package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class Choice {
    public static Choice of(FinishReason finishReason, Message message) {
        var choice = new Choice();
        choice.message = message;
        choice.finishReason = finishReason;
        return choice;
    }

    @NotNull
    @Property(name = "finish_reason")
    public FinishReason finishReason;
    @Property(name = "message")
    public Message message;
    @Property(name = "delta")
    public Message delta;
    @Property(name = "index")
    public Integer index;
}
