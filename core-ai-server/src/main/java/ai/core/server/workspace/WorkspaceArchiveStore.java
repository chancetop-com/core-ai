package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence contract for {@code app.workspace_archives}.
 *
 * @author stephen
 */
public interface WorkspaceArchiveStore {
    Optional<WorkspaceArchiveRecord> get(String id);

    List<WorkspaceArchiveRecord> listVisible(String workspaceId);

    List<WorkspaceArchiveRecord> listByWorkspace(String workspaceId);

    List<WorkspaceArchiveRecord> listExpired(ZonedDateTime createdBefore, int limit);

    Map<String, WorkspaceArchiveSummary> summarizeReady(List<String> workspaceIds);

    boolean insertIfAbsent(WorkspaceArchiveRecord archive);

    boolean markReady(String id, String sha256, long sizeBytes, Long fileCount);

    boolean markFailed(String id, String failureReason);

    boolean markDeleted(String id);
}
