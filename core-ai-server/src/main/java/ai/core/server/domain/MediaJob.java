package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author Stephen
 */
@Collection(name = "media_jobs")
public class MediaJob {
    @Id
    public String id;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "agent_run_id")
    public String agentRunId;

    @Field(name = "provider_id")
    public String providerId;

    @Field(name = "upstream_video_id")
    public String upstreamVideoId;

    @Field(name = "parent_job_id")
    public String parentJobId;

    @Field(name = "requested_model")
    public String requestedModel;

    @Field(name = "resolved_model")
    public String resolvedModel;

    @Field(name = "state")
    public String state;

    @Field(name = "progress")
    public Integer progress;

    @Field(name = "error")
    public String error;

    @Field(name = "file_id")
    public String fileId;

    @Field(name = "file_name")
    public String fileName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;
}
