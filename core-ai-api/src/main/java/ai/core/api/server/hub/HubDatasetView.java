package ai.core.api.server.hub;

import ai.core.api.server.dataset.SchemaFieldView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class HubDatasetView {
    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "name")
    public String name;

    // GENERAL | SESSION
    @Property(name = "type")
    public String type;

    // READ | WRITE | FULL
    @Property(name = "permission")
    public String permission;

    @Property(name = "description")
    public String description;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;
}
