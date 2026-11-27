package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * Cached filesystem usage sample of a workspace volume.
 *
 * @author stephen
 */
public class WorkspaceUsageRecord {
    public String workspaceId;
    public Long usedBytes;
    public Long capacityBytes;
    public Long availableBytes;
    public Long inodesUsed;
    public Long inodesTotal;
    public Long volumeGeneration;
    public ZonedDateTime sampledAt;
}
