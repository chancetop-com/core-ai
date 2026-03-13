package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class UpdateMcpServerRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "category")
    public String category;

    @Property(name = "config")
    public Map<String, String> config;

    @Property(name = "enabled")
    public Boolean enabled;
}
