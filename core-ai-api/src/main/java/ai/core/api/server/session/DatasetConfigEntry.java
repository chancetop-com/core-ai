package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DatasetConfigEntry {
    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "permission")
    public String permission;

    @Property(name = "is_output")
    public Boolean isOutput;
}
