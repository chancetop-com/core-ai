package ai.core.api.mcp.schema;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ToolCapabilities {
    @NotNull
    @Property(name = "listChanged")
    public Boolean listChanged = Boolean.FALSE;
}
