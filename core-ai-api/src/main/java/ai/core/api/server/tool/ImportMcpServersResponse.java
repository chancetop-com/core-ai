package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ImportMcpServersResponse {
    @Property(name = "servers")
    public List<ToolRegistryView> servers;

    @Property(name = "total")
    public Integer total;
}
