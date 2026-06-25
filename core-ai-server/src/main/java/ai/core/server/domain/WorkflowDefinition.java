package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * The editable draft of a workflow. Auto-save overwrites {@link #draftGraph}; an explicit "save version" captures
 * that draft into an immutable {@link WorkflowPublishedVersion}. The public workflow is only a pointer to one
 * saved version, so editing the draft never changes what API callers or WORKFLOW nodes run.
 *
 * @author Xander
 */
@Collection(name = "workflow_definitions")
public class WorkflowDefinition {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @Field(name = "mode")
    public WorkflowMode mode;

    @Field(name = "draft_graph")
    public String draftGraph;

    @Field(name = "visibility")
    public WorkflowVisibility visibility;

    @Field(name = "status")
    public WorkflowDefinitionStatus status;

    @Field(name = "published_version_id")
    public String publishedVersionId;

    @Field(name = "published_version")
    public Integer publishedVersion;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "archived_at")
    public ZonedDateTime archivedAt;

    @Field(name = "archived_by")
    public String archivedBy;
}
