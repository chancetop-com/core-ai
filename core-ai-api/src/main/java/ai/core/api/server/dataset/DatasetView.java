package ai.core.api.server.dataset;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class DatasetView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
