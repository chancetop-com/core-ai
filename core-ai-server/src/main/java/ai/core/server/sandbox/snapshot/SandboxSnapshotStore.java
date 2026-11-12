package ai.core.server.sandbox.snapshot;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public interface SandboxSnapshotStore {
    void insert(SandboxSnapshotDoc doc);

    SandboxSnapshotDoc findLatestAvailable(String sessionId);

    List<SandboxSnapshotDoc> listExpired(ZonedDateTime now, int limit);

    List<SandboxSnapshotDoc> listBySession(String sessionId);

    boolean markAvailable(String id);

    void tombstone(String id);

    void delete(String id);
}
