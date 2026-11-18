package ai.core.api.server.plugin;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class PluginRegistrySearchRequest {
    @Property(name = "query")
    public String query;

    @Property(name = "offset")
    public Integer offset;

    @Property(name = "limit")
    public Integer limit;
}
