package ai.core.server.workspace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkspaceUsageStore {
    Optional<WorkspaceUsageRecord> get(String workspaceId);

    Map<String, WorkspaceUsageRecord> listByWorkspaces(List<String> workspaceIds);

    void upsert(WorkspaceUsageRecord usage);

    void delete(String workspaceId);
}
