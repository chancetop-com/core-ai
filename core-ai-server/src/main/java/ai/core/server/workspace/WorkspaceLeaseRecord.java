package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * The single-writer gate of a workspace, persisted in {@code app.workspace_leases}. A turn lease is
 * held by the session whose turn currently executes tools; a maintenance lease is held by the
 * sleeper. The row is replaceable only when the current holder's lease expired or its session is no
 * longer running a turn, so a crashed pod cannot pin a workspace forever.
 *
 * @author stephen
 */
public class WorkspaceLeaseRecord {
    public String workspaceId;
    public String holderId;
    public String leaseToken;
    public String leaseKind;
    public String sessionId;
    public String turnId;
    public Long runtimeGeneration;
    public ZonedDateTime expiresAt;
    public ZonedDateTime renewedAt;

    public boolean expired(ZonedDateTime now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}
