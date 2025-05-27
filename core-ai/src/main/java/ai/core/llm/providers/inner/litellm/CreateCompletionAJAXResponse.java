package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;


/**
 * @author stephen
 */
public class CreateCompletionAJAXResponse {
    @NotNull
    @Property(name = "choices")
    public List<ChoiceAJAXView> choices;

    @NotNull
    @Property(name = "usage")
    public UsageAJAXView usage;
}
