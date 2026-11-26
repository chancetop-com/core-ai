package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkspaceBindingStore {
    Optional<WorkspaceBindingRecord> get(String workspaceId);

    /** replaces the runtime pointer; {@code boundSessionId == null} clears the hub binding */
    void upsert(WorkspaceBindingRecord binding);

    /** @return true when this session was the current binder */
    boolean clearSessionIfBound(String workspaceId, String sessionId, ZonedDateTime at);

    /** @return true when the runtime pointer was still the expected one, so the caller owns the turn lease */
    boolean rebindSession(String workspaceId, String sandboxId, long runtimeGeneration, String sessionId, ZonedDateTime at);

    void delete(String workspaceId);
}
