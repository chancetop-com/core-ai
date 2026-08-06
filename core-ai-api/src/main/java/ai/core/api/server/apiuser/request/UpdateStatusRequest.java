package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class UpdateStatusRequest {
    @NotNull
    @Property(name = "status")
    public String status;
}
