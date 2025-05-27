package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ParameterAJAXView {
    @NotNull
    @Property(name = "type")
    public String type;

    @Property(name = "enum")
    public List<String> enums;

    @NotNull
    @Property(name = "properties")
    public Map<String, PropertyAJAXView> properties;

    @NotNull
    @Property(name = "required")
    public List<String> required;
}
