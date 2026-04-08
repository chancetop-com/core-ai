package ai.core.api.server.agent;

import ai.core.api.jsonschema.JsonSchema;
import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class ConvertJavaToSchemaResponse {
    @Property(name = "schema")
    public JsonSchema schema;

    @Property(name = "error")
    public String error;
}
