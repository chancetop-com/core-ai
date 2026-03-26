package ai.core.server.apimcp.serviceapi.domain;

import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

import java.util.List;

/**
 * @author stephen
 */
public class OperationAdditional {
    @NotNull
    @NotBlank
    @Field(name = "name")
    public String name;
    @Field(name = "description")
    public String description;
    @Field(name = "example")
    public String example;
    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = true;
    @Field(name = "need_auth")
    public Boolean needAuth;
    @Field(name = "path_param_additional")
    public List<PathParamAdditional> pathParamAdditional;
}
