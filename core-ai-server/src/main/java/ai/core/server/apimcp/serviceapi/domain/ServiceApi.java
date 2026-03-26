package ai.core.server.apimcp.serviceapi.domain;

import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
@Collection(name = "service_api")
public class ServiceApi {
    @Id
    public String id;
    @NotNull
    @NotBlank
    @Field(name = "name")
    public String name;
    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = true;
    @Field(name = "base_url")
    public String baseUrl;
    @NotNull
    @NotBlank
    @Field(name = "version")
    public String version;
    @Field(name = "description")
    public String description;
    @Field(name = "url")
    public String url;
    @Field(name = "payload")
    public String payload;
    @Field(name = "service_additional")
    public List<ServiceAdditional> serviceAdditional;
    @Field(name = "field_additional")
    public List<TypeAdditional> typeAdditional;
    @Field(name = "created_by")
    public String createdBy;
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
    @Field(name = "updated_by")
    public String updatedBy;
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
