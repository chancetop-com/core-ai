package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class Tool {
    @NotNull
    @Property(name = "type")
    public ToolType type;
    @Property(name = "function")
    public Function function;
}
