package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class TypeAdditionalView {
    @NotNull
    @NotBlank
    @Property(name = "name")
    public String name;
    @Property(name = "field_additional")
    public List<FieldAdditionalView> fieldAdditional;
}
