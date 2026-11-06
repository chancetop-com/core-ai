package ai.core.api.server.hub;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class HubDatasetRenameRequest {
    @NotNull
    @Property(name = "from")
    public String from;

    @NotNull
    @Property(name = "to")
    public String to;
}
