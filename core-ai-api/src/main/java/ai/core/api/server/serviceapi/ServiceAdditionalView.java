package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ServiceAdditionalView {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;
    @Property(name = "description")
    public String description;
    @Property(name = "enabled")
    public Boolean enabled;
    @Property(name = "operation_additional")
    public List<OperationAdditionalView> operationAdditional;
}
