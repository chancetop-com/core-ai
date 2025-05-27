package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FunctionAJAXView {
    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "description")
    public String description;

    @NotNull
    @Property(name = "parameters")
    public ParameterAJAXView parameters;
}
