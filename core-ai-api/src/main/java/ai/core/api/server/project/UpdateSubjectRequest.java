package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateSubjectRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "external_link")
    public String externalLink;

    @Property(name = "status")
    public String status;   // started | paused (paused subjects are skipped by scheduled analysis)
}
