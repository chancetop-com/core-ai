package ai.core.llm.providers.inner;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class PropertyView {
    @NotNull
    @Property(name = "type")
    public ParameterTypeView type;

    @NotNull
    @Property(name = "description")
    public String description;
}
