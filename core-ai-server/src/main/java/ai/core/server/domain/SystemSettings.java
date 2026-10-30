package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "system_settings")
public class SystemSettings {
    @Id
    public String id;

    @Field(name = "memory_extraction_model")
    public String memoryExtractionModel;

    @Field(name = "llm_model")
    public String llmModel;

    @Field(name = "caption_image_model")
    public String captionImageModel;

    @Field(name = "summarize_pdf_model")
    public String summarizePdfModel;

    @Field(name = "image_generation_model")
    public String imageGenerationModel;

    @Field(name = "video_generation_model")
    public String videoGenerationModel;

    @Field(name = "video_understanding_model")
    public String videoUnderstandingModel;

    @Field(name = "sandbox_snapshot_enabled")
    public Boolean sandboxSnapshotEnabled;

    @Field(name = "sandbox_attachment_max_bytes")
    public Long sandboxAttachmentMaxBytes;

    @Field(name = "sandbox_snapshot_retention_days")
    public Integer sandboxSnapshotRetentionDays;

    @Field(name = "skill_repo_sync_enabled")
    public Boolean skillRepoSyncEnabled;

    @Field(name = "skill_repo_sync_interval_minutes")
    public Integer skillRepoSyncIntervalMinutes;

    @Field(name = "trace_archive_interval_minutes")
    public Integer traceArchiveIntervalMinutes;

    @Field(name = "storage_provider")
    public String storageProvider;

    @Field(name = "azure_blob_artifact_container")
    public String azureBlobArtifactContainer;

    @Field(name = "azure_blob_public_artifact_container")
    public String azureBlobPublicArtifactContainer;

    @Field(name = "azure_blob_account_name")
    public String azureBlobAccountName;

    @Field(name = "azure_blob_account_key")
    public String azureBlobAccountKey;

    @Field(name = "azure_blob_multimodal_container")
    public String azureBlobMultimodalContainer;

    @Field(name = "azure_blob_public_base_url")
    public String azureBlobPublicBaseUrl;

    @Field(name = "azure_blob_cdn_base_url")
    public String azureBlobCdnBaseUrl;

    @Field(name = "azure_speech_key")
    public String azureSpeechKey;

    @Field(name = "azure_speech_region")
    public String azureSpeechRegion;

    @Field(name = "azure_speech_endpoint")
    public String azureSpeechEndpoint;

    @Field(name = "github_app_id")
    public String githubAppId;

    @Field(name = "github_app_installation_id")
    public String githubAppInstallationId;

    @Field(name = "github_app_private_key")
    public String githubAppPrivateKey;

    @Field(name = "rbac_roles")
    public String rbacRoles;

    // role -> trace scope mode (self|api|all); non-self entries only, default is self
    @Field(name = "rbac_trace_scopes")
    public String rbacTraceScopes;

    @Field(name = "runtime_admission_enabled")
    public Boolean runtimeAdmissionEnabled;

    @Field(name = "runtime_heap_watermark_percent")
    public Integer runtimeHeapWatermarkPercent;

    @Field(name = "runtime_max_in_flight_turns_per_pod")
    public Integer runtimeMaxInFlightTurnsPerPod;

    @Field(name = "runtime_max_in_flight_turns_global")
    public Integer runtimeMaxInFlightTurnsGlobal;

    @Field(name = "runtime_max_sessions_per_pod")
    public Integer runtimeMaxSessionsPerPod;

    @Field(name = "runtime_max_sessions_per_user")
    public Integer runtimeMaxSessionsPerUser;

    @Field(name = "runtime_max_concurrent_turns_per_user")
    public Integer runtimeMaxConcurrentTurnsPerUser;

    @Field(name = "runtime_max_queued_messages_per_session")
    public Integer runtimeMaxQueuedMessagesPerSession;

    @Field(name = "runtime_turn_timeout_seconds")
    public Integer runtimeTurnTimeoutSeconds;

    @Field(name = "runtime_sse_max_queue_depth")
    public Integer runtimeSseMaxQueueDepth;

    @Field(name = "runtime_sse_max_queue_bytes")
    public Long runtimeSseMaxQueueBytes;

    @Field(name = "runtime_run_queue_capacity")
    public Integer runtimeRunQueueCapacity;

    @Field(name = "runtime_max_sandboxes_per_pod")
    public Integer runtimeMaxSandboxesPerPod;

    @Field(name = "runtime_max_sandboxes_per_user")
    public Integer runtimeMaxSandboxesPerUser;

    @Field(name = "runtime_max_sandbox_memory_mb_per_user")
    public Integer runtimeMaxSandboxMemoryMbPerUser;

    @Field(name = "runtime_max_sandboxes_per_agent")
    public Integer runtimeMaxSandboxesPerAgent;

    @Field(name = "runtime_span_ingest_queue_capacity")
    public Integer runtimeSpanIngestQueueCapacity;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
