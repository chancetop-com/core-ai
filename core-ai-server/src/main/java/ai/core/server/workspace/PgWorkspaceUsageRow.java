package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceUsageRow {
    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "used_bytes")
    public Long usedBytes;

    @Column(name = "capacity_bytes")
    public Long capacityBytes;

    @Column(name = "available_bytes")
    public Long availableBytes;

    @Column(name = "inodes_used")
    public Long inodesUsed;

    @Column(name = "inodes_total")
    public Long inodesTotal;

    @Column(name = "volume_generation")
    public Long volumeGeneration;

    @Column(name = "sampled_at")
    public ZonedDateTime sampledAt;
}
