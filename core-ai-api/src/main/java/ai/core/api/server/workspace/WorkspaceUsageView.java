package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class WorkspaceUsageView {
    @Property(name = "used_bytes")
    public Long usedBytes;

    @Property(name = "capacity_bytes")
    public Long capacityBytes;

    @Property(name = "available_bytes")
    public Long availableBytes;

    @Property(name = "inodes_used")
    public Long inodesUsed;

    @Property(name = "inodes_total")
    public Long inodesTotal;

    @NotNull
    @Property(name = "sampled_at")
    public ZonedDateTime sampledAt;
}
