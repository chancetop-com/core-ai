package ai.core.api.server.dataset;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateDatasetRequest {
    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;
}
