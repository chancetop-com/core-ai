package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class UpdateApiRequest {
    @Property(name = "enabled")
    public Boolean enabled;
    @Property(name = "description")
    public String description;
    @Property(name = "url")
    public String url;
    @Property(name = "base_url")
    public String baseUrl;
    @Property(name = "payload")
    public String payload;
    @Property(name = "service_additional")
    public List<ServiceAdditionalView> serviceAdditional;
    @Property(name = "type_additional")
    public List<TypeAdditionalView> typeAdditional;
    @NotNull
    @Property(name = "operator")
    public String operator;
}
