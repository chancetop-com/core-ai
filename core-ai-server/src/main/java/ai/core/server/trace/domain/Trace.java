package ai.core.server.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author Xander
 */
@Collection(name = "traces")
public class Trace {
    @Id
    public String id;

    @Field(name = "trace_id")
    public String traceId;

    @Field(name = "name")
    public String name;

    @Field(name = "model")
    public String model;

    @Field(name = "agent_name")
    public String agentName;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "status")
    public TraceStatus status;

    @Field(name = "input")
    public String input;

    @Field(name = "output")
    public String output;

    @Field(name = "metadata")
    public Map<String, String> metadata;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
