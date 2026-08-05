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

    // GENERAL (default) | SESSION — session datasets store per-session state for get_session_state/set_session_state
    @Property(name = "type")
    public String type;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;
}
