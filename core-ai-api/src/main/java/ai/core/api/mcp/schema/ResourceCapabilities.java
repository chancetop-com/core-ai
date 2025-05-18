package ai.core.api.mcp.schema;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ResourceCapabilities {
    @NotNull
    @Property(name = "subscribe")
    public Boolean subscribe = Boolean.FALSE;

    @NotNull
    @Property(name = "listChanged")
    public Boolean listChanged = Boolean.FALSE;
}
