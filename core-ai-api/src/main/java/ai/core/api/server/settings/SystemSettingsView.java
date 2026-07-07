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

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
