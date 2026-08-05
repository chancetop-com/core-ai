package ai.core.api.server.dataset;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class UpdateDatasetRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    // GENERAL | SESSION
    @Property(name = "type")
    public String type;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;
}
