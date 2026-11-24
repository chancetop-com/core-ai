package ai.core.server.workspace;

import java.util.List;

/**
 * @author stephen
 */
public interface WorkspaceEventStore {
    void append(WorkspaceEventRecord event);

    List<WorkspaceEventRecord> list(String workspaceId, int limit);
}
