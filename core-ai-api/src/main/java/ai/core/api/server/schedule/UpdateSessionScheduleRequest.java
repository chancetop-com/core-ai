package ai.core.api.server.schedule;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class UpdateSessionScheduleRequest {
    @NotNull
    @Property(name = "enabled")
    public Boolean enabled;
}
