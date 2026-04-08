package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class ConvertJavaToSchemaResponse {
    @Property(name = "schema")
    public String schema;

    @Property(name = "error")
    public String error;
}
