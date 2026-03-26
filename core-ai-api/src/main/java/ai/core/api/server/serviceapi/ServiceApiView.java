package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class ServiceApiView {
    @Property(name = "id")
    public String id;
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;
    @Property(name = "enabled")
    public Boolean enabled;
    @Property(name = "description")
    public String description;
    @Property(name = "base_url")
    public String baseUrl;
    @Property(name = "url")
    public String url;
    @Property(name = "version")
    public String version;
    @Property(name = "payload")
    public String payload;
    @Property(name = "service_additional")
    public List<ServiceAdditionalView> serviceAdditional;
    @Property(name = "type_additional")
    public List<TypeAdditionalView> typeAdditional;
    @Property(name = "created_by")
    public String createdBy;
    @Property(name = "created_at")
    public ZonedDateTime createdAt;
    @Property(name = "updated_by")
    public String updatedBy;
    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
