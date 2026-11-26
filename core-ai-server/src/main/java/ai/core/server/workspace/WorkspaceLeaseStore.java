package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkspaceLeaseStore {
    Optional<WorkspaceLeaseRecord> get(String workspaceId);

    /** @return false when another live holder already owns the row */
    boolean insert(WorkspaceLeaseRecord lease);

    /** replaces the row only while it still carries {@code expectedToken}, so a concurrent takeover loses */
    boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord lease);

    boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt);

    boolean release(String workspaceId, String leaseToken);

    int releaseBySession(String sessionId);

    int releaseByWorkspace(String workspaceId);
}
