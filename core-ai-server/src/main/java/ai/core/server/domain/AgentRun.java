package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "agent_runs")
public class AgentRun {
    @Id
    public String id;

    @NotNull
    @Field(name = "agent_id")
    public String agentId;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "triggered_by")
    public TriggerType triggeredBy;

    @NotNull
    @Field(name = "status")
    public RunStatus status;

    @Field(name = "input")
    public String input;

    @Field(name = "output")
    public String output;

    @Field(name = "transcript")
    public List<TranscriptEntry> transcript;

    @Field(name = "artifacts")
    public List<AgentRunArtifact> artifacts;

    @Field(name = "token_usage")
    public TokenUsage tokenUsage;

    @Field(name = "error")
    public String error;

    @NotNull
    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;
}
