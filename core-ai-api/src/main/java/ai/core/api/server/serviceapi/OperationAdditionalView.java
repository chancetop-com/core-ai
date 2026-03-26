package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class OperationAdditionalView {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;
    @Property(name = "description")
    public String description;
    @Property(name = "example")
    public String example;
    @Property(name = "enabled")
    public Boolean enabled;
    @Property(name = "need_auth")
    public Boolean needAuth;
    @Property(name = "path_param_additional")
    public List<PathParamAdditionalView> pathParamAdditional;
}
