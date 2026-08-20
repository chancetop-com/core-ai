package ai.core.api.server.project;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * Adds an agent or workflow to a project (the membership write path; the agent/workflow editors
 * deliberately do not expose project selection — the project is the container you add things into).
 *
 * @author stephen
 */
public class AddProjectMemberRequest {
    @NotBlank
    @Property(name = "type")
    public String type;   // agent | workflow

    @NotBlank
    @Property(name = "id")
    public String id;
}
