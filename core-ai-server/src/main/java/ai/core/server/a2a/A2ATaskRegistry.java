package ai.core.server.a2a;

import ai.core.a2a.A2ATaskState;
import ai.core.server.messaging.SessionOwnershipRegistry;

import java.time.Duration;

/**
 * Stores A2A task snapshots so any server Pod can answer task lookups and route
 * task mutations back to the Pod that owns the underlying AgentSession.
 *
 * @author xander
 */
public class A2ATaskRegistry {
    private static final Duration ACTIVE_TASK_TTL = Duration.ofHours(2);
    private static final Duration TERMINAL_TASK_TTL = Duration.ofMinutes(30);

    private final A2ATaskSnapshotStore store;
    private final SessionOwnershipRegistry ownershipRegistry;

    protected A2ATaskRegistry() {
        this.store = null;
        this.ownershipRegistry = null;
    }

    public A2ATaskRegistry(A2ATaskSnapshotStore store, SessionOwnershipRegistry ownershipRegistry) {
        this.store = store;
        this.ownershipRegistry = ownershipRegistry;
    }

    public void save(A2ATaskState state) {
        if (state == null || state.taskId == null) return;
        var ownerPod = ownerPod(state.contextId);
        save(A2ATaskSnapshot.from(state, ownerPod));
    }

    public void save(A2ATaskSnapshot snapshot) {
        if (store == null || snapshot == null || snapshot.taskId == null) return;
        var ttl = snapshot.isTerminal() ? TERMINAL_TASK_TTL : ACTIVE_TASK_TTL;
        store.save(snapshot, ttl);
    }

    public A2ATaskSnapshot get(String taskId) {
        if (store == null || taskId == null || taskId.isBlank()) return null;
        return store.get(taskId);
    }

    public boolean isLocalOwner(A2ATaskSnapshot snapshot) {
        if (snapshot == null || ownershipRegistry == null) return true;
        if (snapshot.contextId != null && ownershipRegistry.isOwner(snapshot.contextId)) return true;
        return snapshot.ownerPod != null && snapshot.ownerPod.equals(ownershipRegistry.getHostname());
    }

    private String ownerPod(String contextId) {
        if (ownershipRegistry == null) return null;
        if (contextId != null && !contextId.isBlank()) {
            ownershipRegistry.claimOrRenew(contextId);
            var owner = ownershipRegistry.getOwner(contextId);
            if (owner != null && !owner.isBlank()) return owner;
        }
        return ownershipRegistry.getHostname();
    }
}
