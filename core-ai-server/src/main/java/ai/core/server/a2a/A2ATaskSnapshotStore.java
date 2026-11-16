package ai.core.server.a2a;

import java.time.Duration;

/**
 * A2A task snapshots are small, expiring documents that any pod must be able to read so a task
 * lookup does not depend on the pod that created it.
 *
 * @author stephen
 */
public interface A2ATaskSnapshotStore {
    void save(A2ATaskSnapshot snapshot, Duration ttl);

    A2ATaskSnapshot get(String taskId);
}
