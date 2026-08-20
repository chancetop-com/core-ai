package ai.core.api.server.project;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * @author stephen
 */
public class ProjectReportSourceRequest {
    @NotBlank
    @Property(name = "type")
    public String type;   // agent | workflow

    @NotBlank
    @Property(name = "id")
    public String id;
}
