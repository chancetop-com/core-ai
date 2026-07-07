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

    @Field(name = "llm_model_multimodal")
    public String llmMultiModalModel;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
