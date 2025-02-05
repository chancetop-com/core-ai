package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ChoiceAJAXView {
    @NotNull
    @Property(name = "finish_reason")
    public FinishReasonAJAXVIEW finishReason;

    @NotNull
    @Property(name = "index")
    public Integer index;

    @NotNull
    @Property(name = "message")
    public MessageAJAXView message;
}
