package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class WorkflowVersionView {
    @Property(name = "id")
    public String id;

    @Property(name = "workflow_id")
    public String workflowId;

    @Property(name = "version")
    public Integer version;

    @Property(name = "preview")
    public Boolean preview;

    @Property(name = "status")
    public String status;

    @Property(name = "sha256")
    public String sha256;

    @Property(name = "published_by")
    public String publishedBy;

    @Property(name = "published_at")
    public ZonedDateTime publishedAt;

    @Property(name = "current_public")
    public Boolean currentPublic;
}
