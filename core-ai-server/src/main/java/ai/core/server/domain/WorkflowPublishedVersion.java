package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * An immutable published snapshot — the authoritative config a WorkflowRun pins and the engine loads. The
 * frozen graph JSON is sha256-verified on load. Referenced Agent snapshots are embedded here (not by agent_id)
 * so a later Agent re-publish cannot drift an already-published workflow version. Mirrors AgentPublishedConfig.
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
