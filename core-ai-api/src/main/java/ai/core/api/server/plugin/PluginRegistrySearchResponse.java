package ai.core.api.server.plugin;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class PluginRegistrySearchResponse {
    @Property(name = "plugins")
    public List<PluginRegistryEntryView> plugins;

    @Property(name = "total")
    public Long total;
}
