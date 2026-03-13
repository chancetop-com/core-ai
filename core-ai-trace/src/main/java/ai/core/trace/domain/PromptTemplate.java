package ai.core.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author Xander
 */
@Collection(name = "prompt_templates")
public class PromptTemplate {
    @Id
    public String id;

    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @Field(name = "template")
    public String template;

    @Field(name = "variables")
    public List<String> variables;

    @Field(name = "model")
    public String model;

    @Field(name = "model_parameters")
    public Map<String, String> modelParameters;

    @Field(name = "version")
    public Integer version;

    @Field(name = "published_version")
    public Integer publishedVersion;

    @Field(name = "status")
    public PromptStatus status;

    @Field(name = "tags")
    public List<String> tags;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
