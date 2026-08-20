package ai.core.api.server.project;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * @author stephen
 */
public class CreateProjectRequest {
    @NotBlank
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "goal")
    public String goal;
}
