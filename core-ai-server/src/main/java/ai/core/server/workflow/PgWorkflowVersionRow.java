package ai.core.server.workflow;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkflowVersionRow {
    @Column(name = "id")
    public String id;

    @Column(name = "workflow_id")
    public String workflowId;

    @Column(name = "version")
    public Integer version;

    @Column(name = "preview")
    public Boolean preview;

    @Column(name = "status")
    public String status;

    @Column(name = "sha256")
    public String sha256;

    @Column(name = "published_by")
    public String publishedBy;

    @Column(name = "published_at")
    public ZonedDateTime publishedAt;

    @Column(name = "data")
    public String data;
}
