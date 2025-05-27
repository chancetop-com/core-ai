package ai.core.llm.domain;

import ai.core.api.jsonschema.JsonSchema;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class Function {
    @NotNull
    @Property(name = "name")
    public String name;
    @NotNull
    @Property(name = "description")
    public String description;
    @NotNull
    @Property(name = "parameters")
    public JsonSchema parameters;
}
