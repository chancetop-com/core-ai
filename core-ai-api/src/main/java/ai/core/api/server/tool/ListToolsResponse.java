package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListToolsResponse {
    @Property(name = "tools")
    public List<ToolRegistryView> tools;

    @Property(name = "total")
    public Long total;
}
