package ai.core.server.workflow;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkflowDefinitionRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "name")
    public String name;

    @Column(name = "mode")
    public String mode;

    @Column(name = "draft_graph")
    public String draftGraph;

    @Column(name = "visibility")
    public String visibility;

    @Column(name = "status")
    public String status;

    @Column(name = "published_version_id")
    public String publishedVersionId;

    @Column(name = "published_version")
    public Integer publishedVersion;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "archived_at")
    public ZonedDateTime archivedAt;

    @Column(name = "archived_by")
    public String archivedBy;

    @Column(name = "data")
    public String data;
}
