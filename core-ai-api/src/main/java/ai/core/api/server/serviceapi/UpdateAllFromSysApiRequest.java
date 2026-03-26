package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class UpdateAllFromSysApiRequest {
    @NotNull
    @Property(name = "operator")
    public String operator;
}
