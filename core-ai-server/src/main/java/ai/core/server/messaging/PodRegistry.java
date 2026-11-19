package ai.core.server.messaging;

import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Registers this pod and keeps its heartbeat fresh. Every lease in the PostgreSQL backend is valid
 * only while the owner pod's heartbeat is fresh, so this row is the foundation of ownership,
 * turn state and session-budget counting.
 *
 * @author stephen
 */
public class PodRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodRegistry.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    @Inject
    public Database database;

    private final String podId;
    private final String address;
    private volatile boolean running = true;
    private Thread heartbeatThread;

    public PodRegistry(String podId, String address) {
        this.podId = podId;
        this.address = address;
    }

    public String podId() {
        return podId;
    }

    public String host() {
        var separator = address.lastIndexOf(':');
        return separator > 0 ? address.substring(0, separator) : address;
    }

    public String address(String podId) {
        try {
            return database.selectOne("SELECT address FROM messaging.pods WHERE pod_id = ? "
                    + "AND heartbeat_at > now() - make_interval(secs => ?)",
                PgPodAddressRow.class, podId, PgOwnershipStore.LIVENESS_SECONDS)
                .map(row -> row.address)
                .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resolve pod address, podId={}", podId, e);
            return null;
        }
    }

    public void start() {
        // command_backend stays in the row for old pods during a rolling upgrade; new pods always write postgres
        database.execute("INSERT INTO messaging.pods (pod_id, address, command_backend, started_at, heartbeat_at) "
            + "VALUES (?, ?, ?, now(), now()) "
            + "ON CONFLICT (pod_id) DO UPDATE SET address = EXCLUDED.address, command_backend = EXCLUDED.command_backend, "
            + "started_at = EXCLUDED.started_at, heartbeat_at = now()",
            podId, address, "postgres");
        heartbeatThread = Thread.ofVirtual().name("pod-heartbeat-" + podId).start(this::heartbeatLoop);
        LOGGER.info("pod registered, podId={}, address={}", podId, address);
    }

    public void stop() {
        running = false;
        if (heartbeatThread != null) heartbeatThread.interrupt();
        try {
            database.execute("DELETE FROM messaging.pods WHERE pod_id = ?", podId);
            LOGGER.info("pod deregistered, podId={}", podId);
        } catch (RuntimeException e) {
            // the row expires by heartbeat; a failed delete only delays reclaiming the leases
            LOGGER.warn("failed to deregister pod, podId={}", podId, e);
        }
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) return;
            try {
                database.execute("UPDATE messaging.pods SET heartbeat_at = now() WHERE pod_id = ?", podId);
            } catch (RuntimeException e) {
                LOGGER.warn("pod heartbeat failed, podId={}", podId, e);
            }
        }
    }
}
