package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateImageCompletionAJAXRequest {
    @NotNull
    @Property(name = "model")
    public String model = "gpt-3.5-turbo-instruct";

    @Property(name = "__image_completion_flag__")
    public Boolean imageCompletionFlag;

    @NotNull
    @Property(name = "messages")
    public List<ImageMessageAJAXView> messages;

    @NotNull
    @Property(name = "temperature")
    public Double temperature = 0.7d;
}
