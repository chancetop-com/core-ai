package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class PropertyAJAXView {
    @NotNull
    @Property(name = "type")
    public String type;

    @NotNull
    @Property(name = "description")
    public String description;

    @Property(name = "format")
    public String format;

    @Property(name = "enum")
    public List<String> enums;

    @Property(name = "items")
    public ParameterAJAXView items;
}
