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

    @Property(name = "llm_model_multimodal")
    public String llmMultiModalModel;

    @Property(name = "caption_image_model")
    public String captionImageModel;

    @Property(name = "image_generation_model")
    public String imageGenerationModel;

    @Property(name = "video_generation_model")
    public String videoGenerationModel;

    @Property(name = "video_understanding_model")
    public String videoUnderstandingModel;
}
