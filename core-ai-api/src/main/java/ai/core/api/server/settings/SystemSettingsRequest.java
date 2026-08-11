package ai.core.api.server.settings;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SystemSettingsRequest {
    @Property(name = "memory_extraction_model")
    public String memoryExtractionModel;

    @Property(name = "llm_model")
    public String llmModel;

    @Property(name = "caption_image_model")
    public String captionImageModel;

    @Property(name = "image_generation_model")
    public String imageGenerationModel;

    @Property(name = "video_generation_model")
    public String videoGenerationModel;

    @Property(name = "video_understanding_model")
    public String videoUnderstandingModel;

    @Property(name = "sandbox_snapshot_enabled")
    public Boolean sandboxSnapshotEnabled;

    @Property(name = "storage_provider")
    public String storageProvider;

    @Property(name = "azure_blob_artifact_container")
    public String azureBlobArtifactContainer;

    @Property(name = "azure_blob_account_name")
    public String azureBlobAccountName;

    @Property(name = "azure_blob_account_key")
    public String azureBlobAccountKey;

    @Property(name = "azure_blob_multimodal_container")
    public String azureBlobMultimodalContainer;

    @Property(name = "azure_blob_public_base_url")
    public String azureBlobPublicBaseUrl;

    @Property(name = "azure_blob_cdn_base_url")
    public String azureBlobCdnBaseUrl;

    @Property(name = "azure_speech_key")
    public String azureSpeechKey;

    @Property(name = "azure_speech_region")
    public String azureSpeechRegion;

    @Property(name = "azure_speech_endpoint")
    public String azureSpeechEndpoint;

    @Property(name = "github_app_id")
    public String githubAppId;

    @Property(name = "github_app_installation_id")
    public String githubAppInstallationId;

    @Property(name = "github_app_private_key")
    public String githubAppPrivateKey;
}
