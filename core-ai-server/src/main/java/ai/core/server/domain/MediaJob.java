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

    // provider-native continuation handle (tier 1): images have no upstream_video_id equivalent
    @Field(name = "upstream_interaction_id")
    public String upstreamInteractionId;

    // upstream-side asset still reachable by the producing provider (tier 2), e.g. a KIE result URL
    // or an asset://{assetId} handle — reusing it moves zero bytes and outlives a signed URL
    @Field(name = "upstream_asset_url")
    public String upstreamAssetUrl;

    @Field(name = "upstream_asset_expires_at")
    public ZonedDateTime upstreamAssetExpiresAt;

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

    @Field(name = "media_type")
    public String mediaType;

    @Field(name = "requested_seconds")
    public Integer requestedSeconds;

    @Field(name = "media_units")
    public Double mediaUnits;

    @Field(name = "media_unit_type")
    public String mediaUnitType;

    @Field(name = "credits_consumed")
    public Double creditsConsumed;

    @Field(name = "cost_usd")
    public Double costUsd;

    @Field(name = "cost_source")
    public String costSource;

    @Field(name = "pricing_model_id")
    public String pricingModelId;
}
