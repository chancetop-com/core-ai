package ai.core.server.apimcp.serviceapi.domain;

import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class PathParamAdditional {
    @NotNull
    @NotBlank
    @Field(name = "name")
    public String name;
    @Field(name = "description")
    public String description;
    @Field(name = "example")
    public String example;
}
