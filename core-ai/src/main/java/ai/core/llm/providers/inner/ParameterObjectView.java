package ai.core.llm.providers.inner;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ParameterObjectView {
    @NotNull
    @Property(name = "type")
    public ParameterTypeView type;

    @NotNull
    @Property(name = "properties")
    public Map<String, PropertyView> properties;

    @NotNull
    @Property(name = "required")
    public List<String> required;
}
