package ai.core.api.server.tool;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class CreateMcpServerRequest {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "category")
    public String category;

    @NotNull
    @Property(name = "config")
    public Map<String, String> config;

    @Property(name = "enabled")
    public Boolean enabled;
}
