package ai.core.api.server.dataset;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RenameDatasetFieldRequest {
    @NotNull
    @Property(name = "from")
    public String from;

    @NotNull
    @Property(name = "to")
    public String to;
}
