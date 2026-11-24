package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkspaceStore {
    Optional<WorkspaceRecord> get(String id);

    Optional<WorkspaceRecord> findDefault(String ownerUserId, String agentId);

    Optional<WorkspaceRecord> findByName(String ownerUserId, String name);

    List<WorkspaceRecord> listByOwner(String ownerUserId);

    /** active workspaces across all owners, for the reconciler / quota repair */
    List<WorkspaceRecord> listActive();

    List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff);

    /** @return false on a unique violation (name or default-agent conflict) or an existing id */
    boolean insertIfAbsent(WorkspaceRecord workspace);

    void update(WorkspaceRecord workspace);

    void updateVolumeState(String id, WorkspaceVolumeStatus status, Long activeVolumeGeneration,
                           String errorCode, String errorMessage);

    void updateRuntimeState(String id, WorkspaceRuntimeStatus status, long runtimeGeneration,
                            String errorCode, String errorMessage);

    void touchLastUsed(String id, ZonedDateTime at);

    boolean softDelete(String id, ZonedDateTime at);

    /** @return false when the row is not deleted (or missing); a name conflict throws */
    boolean restore(String id);

    /** clears the previous default of the same (owner, agent) and marks this row default */
    void makeDefault(String ownerUserId, String agentId, String id);
}
