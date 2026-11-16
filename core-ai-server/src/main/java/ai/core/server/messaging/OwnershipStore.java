package ai.core.server.messaging;

/**
 * Cross-pod lease on a resource (a session or an AgentRun). A lease is valid only while its owner
 * pod is alive; the PostgreSQL implementation derives that from the pod heartbeat instead of
 * renewing each row.
 *
 * @author stephen
 */
public interface OwnershipStore {
    boolean claim(String resourceId, String ownerPod);

    boolean renew(String resourceId, String ownerPod);

    String owner(String resourceId);

    void release(String resourceId, String ownerPod);
}
