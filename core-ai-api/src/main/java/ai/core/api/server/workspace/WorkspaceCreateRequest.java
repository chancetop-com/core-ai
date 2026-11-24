package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.Min;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class WorkspaceCreateRequest {
    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "agent_id")
    public String agentId;

    @Min(1)
    @Property(name = "size_gb")
    public Integer sizeGb;

    @Property(name = "profile")
    public String profile;

    @Min(1)
    @Property(name = "idle_sleep_minutes")
    public Integer idleSleepMinutes;
}
