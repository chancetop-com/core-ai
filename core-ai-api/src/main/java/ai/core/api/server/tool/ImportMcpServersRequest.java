package ai.core.api.server.tool;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ImportMcpServersRequest {
    @NotNull
    @Property(name = "config")
    public String config;

    @Property(name = "category")
    public String category;

    @NotNull
    @Property(name = "enabled")
    public Boolean enabled = true;
}
