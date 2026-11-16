package ai.core.server.messaging;

import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class PgOwnershipStore implements OwnershipStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgOwnershipStore.class);
    // a pod whose heartbeat is older than this is dead; its leases are claimable immediately
    public static final int LIVENESS_SECONDS = 30;

    @Inject
    public Database database;

    @Override
    public boolean claim(String resourceId, String ownerPod) {
        try {
            return database.selectOne("INSERT INTO messaging.session_ownership (resource_id, owner_pod, acquired_at) "
                    + "VALUES (?, ?, now()) "
                    + "ON CONFLICT (resource_id) DO UPDATE SET owner_pod = EXCLUDED.owner_pod, acquired_at = EXCLUDED.acquired_at "
                    + "WHERE messaging.session_ownership.owner_pod = EXCLUDED.owner_pod "
                    + "OR NOT EXISTS (SELECT 1 FROM messaging.pods p "
                    + "WHERE p.pod_id = messaging.session_ownership.owner_pod "
                    + "AND p.heartbeat_at > now() - make_interval(secs => ?)) "
                    + "RETURNING owner_pod",
                PgLeaseOwnerRow.class, resourceId, ownerPod, LIVENESS_SECONDS).isPresent();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to claim ownership (PostgreSQL unavailable), resourceId={}", resourceId, e);
            return false;
        }
    }

    @Override
    public boolean renew(String resourceId, String ownerPod) {
        try {
            return database.execute("UPDATE messaging.session_ownership SET acquired_at = now() "
                    + "WHERE resource_id = ? AND owner_pod = ?", resourceId, ownerPod) > 0;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to renew ownership (PostgreSQL unavailable), resourceId={}", resourceId, e);
            return false;
        }
    }

    @Override
    public String owner(String resourceId) {
        try {
            return database.selectOne("SELECT o.owner_pod FROM messaging.session_ownership o "
                    + "JOIN messaging.pods p ON p.pod_id = o.owner_pod "
                    + "WHERE o.resource_id = ? AND p.heartbeat_at > now() - make_interval(secs => ?)",
                PgLeaseOwnerRow.class, resourceId, LIVENESS_SECONDS)
                .map(row -> row.ownerPod)
                .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to get owner (PostgreSQL unavailable), resourceId={}", resourceId, e);
            return null;
        }
    }

    @Override
    public void release(String resourceId, String ownerPod) {
        try {
            database.execute("DELETE FROM messaging.session_ownership WHERE resource_id = ? AND owner_pod = ?",
                resourceId, ownerPod);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release ownership (PostgreSQL unavailable), resourceId={}", resourceId, e);
        }
    }
}
