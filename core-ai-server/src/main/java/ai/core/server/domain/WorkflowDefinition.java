package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * The editable draft of a workflow. Users only mutate the draft; publish() captures it into an immutable
 * WorkflowPublishedVersion. Mirrors AgentDefinition's draft/published split. The graph is stored as canvas
 * JSON (includes NOTE nodes); the executable graph is parsed from it at publish/run time.
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

    @Field(name = "published_version_id")
    public String publishedVersionId;

    @Field(name = "published_version")
    public Integer publishedVersion;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
