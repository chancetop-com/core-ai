package ai.core.api.server.tool;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class ToolRegistryView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @NotNull
    @Property(name = "type")
    public String type;

    @Property(name = "category")
    public String category;

    @NotNull
    @Property(name = "config")
    public Map<String, String> config;

    @NotNull
    @Property(name = "enabled")
    public Boolean enabled;
}
