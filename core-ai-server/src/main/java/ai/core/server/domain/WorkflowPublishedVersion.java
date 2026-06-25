package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * An immutable workflow snapshot. Non-preview rows are manual versions (v1/v2/...) created by an explicit user save;
 * preview rows are throwaway snapshots used only for Test. Publishing a workflow moves the definition's public
 * pointer to one manual version; it does not mutate the version itself.
 *
 * @author Xander
 */
@Collection(name = "workflow_published_versions")
public class WorkflowPublishedVersion {
    @Id
    public String id;

    @NotNull
    @Field(name = "workflow_id")
    public String workflowId;

    @NotNull
    @Field(name = "version")
    public Integer version;

    // a throwaway snapshot created to run the draft (preview); not promoted to the definition's published version
    @Field(name = "preview")
    public Boolean preview;

    @Field(name = "status")
    public WorkflowVersionStatus status;

    @Field(name = "sha256")
    public String sha256;

    @Field(name = "graph")
    public String graph;

    @Field(name = "env_vars")
    public Map<String, String> envVars;

    // node_id -> serialized AgentPublishedConfig snapshot; populated when AGENT/LLM executors land (1c).
    @Field(name = "agent_snapshots")
    public Map<String, String> agentSnapshots;

    @Field(name = "tool_digests")
    public Map<String, String> toolDigests;

    @Field(name = "published_by")
    public String publishedBy;

    @Field(name = "published_at")
    public ZonedDateTime publishedAt;
}
