package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class PathParamAdditionalView {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;
    @Property(name = "description")
    public String description;
    @Property(name = "example")
    public String example;
}
