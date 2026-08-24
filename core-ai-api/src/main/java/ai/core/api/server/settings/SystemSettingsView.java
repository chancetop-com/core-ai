package ai.core.api.server.settings;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class SystemSettingsView {
    @Property(name = "memory_extraction_model")
    public String memoryExtractionModel;

    @Property(name = "default_memory_extraction_model")
    public String defaultMemoryExtractionModel;

    @Property(name = "llm_model")
    public String llmModel;

    @Property(name = "default_llm_model")
    public String defaultLlmModel;

    @Property(name = "caption_image_model")
    public String captionImageModel;

    @Property(name = "default_caption_image_model")
    public String defaultCaptionImageModel;

    @Property(name = "summarize_pdf_model")
    public String summarizePdfModel;

    @Property(name = "image_generation_model")
    public String imageGenerationModel;

    @Property(name = "default_image_generation_model")
    public String defaultImageGenerationModel;

    @Property(name = "video_generation_model")
    public String videoGenerationModel;

    @Property(name = "default_video_generation_model")
    public String defaultVideoGenerationModel;

    @Property(name = "video_understanding_model")
    public String videoUnderstandingModel;

    @Property(name = "default_video_understanding_model")
    public String defaultVideoUnderstandingModel;

    @Property(name = "sandbox_snapshot_enabled")
    public Boolean sandboxSnapshotEnabled;

    @Property(name = "sandbox_snapshot_deployment_allowed")
    public Boolean sandboxSnapshotDeploymentAllowed;

    @Property(name = "sandbox_snapshot_storage_ready")
    public Boolean sandboxSnapshotStorageReady;

    @Property(name = "sandbox_snapshot_effective")
    public Boolean sandboxSnapshotEffective;

    @Property(name = "storage_provider")
    public String storageProvider;

    @Property(name = "azure_blob_artifact_container")
    public String azureBlobArtifactContainer;

    @Property(name = "azure_blob_account_name")
    public String azureBlobAccountName;

    @Property(name = "has_azure_blob_account_key")
    public Boolean hasAzureBlobAccountKey;

    @Property(name = "azure_blob_multimodal_container")
    public String azureBlobMultimodalContainer;

    @Property(name = "azure_blob_public_base_url")
    public String azureBlobPublicBaseUrl;

    @Property(name = "azure_blob_cdn_base_url")
    public String azureBlobCdnBaseUrl;

    @Property(name = "has_azure_speech_key")
    public Boolean hasAzureSpeechKey;

    @Property(name = "azure_speech_region")
    public String azureSpeechRegion;

    @Property(name = "azure_speech_endpoint")
    public String azureSpeechEndpoint;

    @Property(name = "github_app_id")
    public String githubAppId;

    @Property(name = "github_app_installation_id")
    public String githubAppInstallationId;

    @Property(name = "has_github_app_private_key")
    public Boolean hasGithubAppPrivateKey;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
