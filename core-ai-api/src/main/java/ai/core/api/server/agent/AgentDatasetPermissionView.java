package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class AgentDatasetPermissionView {
    @NotNull
    @Property(name = "dataset_id")
    public String datasetId;

    @NotNull
    @Property(name = "permission")
    public String permission = "READ";
}
