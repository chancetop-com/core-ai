package ai.core.api.server.sandboxhub;

import ai.core.api.server.dataset.SchemaFieldView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * One dataset a session may reach, as listed in the {@code datasets} section of the sandbox catalog.
 * <p>
 * Read-only: the section tells a script which datasets exist and what they look like, never lets it call
 * anything. Writes still go through the dataset tools, so mounting those tools remains the only way to
 * grant a write path.
 *
 * @author stephen
 */
public class SandboxHubDatasetView {
    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "permission")
    public String permission;

    @Property(name = "description")
    public String description;

    @Property(name = "schema")
    public List<SchemaFieldView> schema;
}
