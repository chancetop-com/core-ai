package ai.core.server.workspace;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkspaceVolumeStore {
    Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration);

    Optional<WorkspaceVolumeRecord> active(String workspaceId);

    List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId);

    List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status);

    /** @return false when the (workspace, generation) row already exists */
    boolean insertIfAbsent(WorkspaceVolumeRecord volume);

    void update(WorkspaceVolumeRecord volume);

    void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status);

    void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status);
}
