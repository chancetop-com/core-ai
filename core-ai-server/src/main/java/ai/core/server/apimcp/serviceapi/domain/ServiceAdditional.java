package ai.core.server.apimcp.serviceapi.domain;

import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

import java.util.List;

/**
 * @author stephen
 */
public class ServiceAdditional {
    @NotNull
    @NotBlank
    @Field(name = "name")
    public String name;
    @Field(name = "description")
    public String description;
    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = true;
    @Field(name = "operation_additional")
    public List<OperationAdditional> operationAdditional;
}
