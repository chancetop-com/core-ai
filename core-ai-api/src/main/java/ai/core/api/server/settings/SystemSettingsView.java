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

    @Property(name = "llm_model_multimodal")
    public String llmMultiModalModel;

    @Property(name = "default_llm_model_multimodal")
    public String defaultLlmMultiModalModel;

    @Property(name = "caption_image_model")
    public String captionImageModel;

    @Property(name = "default_caption_image_model")
    public String defaultCaptionImageModel;

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

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
