package ai.core.llm.domain.responses;

import ai.core.api.jsonschema.JsonSchema;
import core.framework.api.json.Property;

/**
 * Flat custom function tool shape used by the Responses API.
 */
public class ResponsesTool {
    @Property(name = "type")
    public String type;
    @Property(name = "name")
    public String name;
    @Property(name = "description")
    public String description;
    @Property(name = "parameters")
    public JsonSchema parameters;
    @Property(name = "strict")
    public Boolean strict;
}
