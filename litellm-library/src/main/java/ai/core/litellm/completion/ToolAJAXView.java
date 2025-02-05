package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ToolAJAXView {
    @NotNull
    @Property(name = "type")
    public ToolTypeAJAXView type;

    @Property(name = "function")
    public FunctionAJAXView function;
}
