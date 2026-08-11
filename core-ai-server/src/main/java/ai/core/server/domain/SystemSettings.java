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

    @Field(name = "image_generation_model")
    public String imageGenerationModel;

    @Field(name = "video_generation_model")
    public String videoGenerationModel;

    @Field(name = "video_understanding_model")
    public String videoUnderstandingModel;

    @Field(name = "sandbox_snapshot_enabled")
    public Boolean sandboxSnapshotEnabled;

    @Field(name = "storage_provider")
    public String storageProvider;

    @Field(name = "azure_blob_artifact_container")
    public String azureBlobArtifactContainer;

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

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
