package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.Min;

/**
 * @author stephen
 */
public class WorkspaceUpdateRequest {
    @Property(name = "name")
    public String name;

    @Min(1)
    @Property(name = "idle_sleep_minutes")
    public Integer idleSleepMinutes;
}
