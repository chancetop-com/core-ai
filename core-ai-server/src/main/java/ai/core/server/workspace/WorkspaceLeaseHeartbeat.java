package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Keeps a turn lease alive while its turn is still executing. Renewal stops as soon as the holder is
 * no longer running a turn, so an abandoned row expires instead of pinning the workspace; a lost
 * token (takeover / release) is reported to the caller, which must stop writing to the volume.
 *
 * @author stephen
 */
public final class WorkspaceLeaseHeartbeat implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceLeaseHeartbeat.class);

    public static WorkspaceLeaseHeartbeat start(ScheduledExecutorService scheduler, WorkspaceLeaseStore leases,
                                                String workspaceId, String leaseToken, int ttlSeconds,
                                                BooleanSupplier holderActive) {
        var heartbeat = new WorkspaceLeaseHeartbeat(leases, workspaceId, leaseToken, ttlSeconds, holderActive);
        heartbeat.future = scheduler.scheduleAtFixedRate(heartbeat::renew, heartbeat.intervalSeconds,
                heartbeat.intervalSeconds, TimeUnit.SECONDS);
        return heartbeat;
    }

    private final WorkspaceLeaseStore leases;
    private final String workspaceId;
    private final String leaseToken;
    private final int ttlSeconds;
    private final int intervalSeconds;
    private final BooleanSupplier holderActive;
    private volatile ScheduledFuture<?> future;
    private volatile boolean lost;

    private WorkspaceLeaseHeartbeat(WorkspaceLeaseStore leases, String workspaceId, String leaseToken,
                                    int ttlSeconds, BooleanSupplier holderActive) {
        this.leases = leases;
        this.workspaceId = workspaceId;
        this.leaseToken = leaseToken;
        this.ttlSeconds = ttlSeconds;
        this.intervalSeconds = Math.max(30, ttlSeconds / 3);
        this.holderActive = holderActive;
    }

    private void renew() {
        var active = holderActive;
        if (active != null && !active.getAsBoolean()) return;   // turn ended: let the lease expire naturally
        try {
            if (!leases.renew(workspaceId, leaseToken, ZonedDateTime.now().plusSeconds(ttlSeconds))) {
                lost = true;
                LOGGER.warn("workspace lease is no longer ours, workspace={}", workspaceId);
            }
        } catch (RuntimeException e) {
            // a transient database failure is retried on the next tick; losing the token is reported by renew()
            LOGGER.warn("failed to renew workspace lease, workspace={}", workspaceId, e);
        }
    }

    public boolean lost() {
        return lost;
    }

    @Override
    public void close() {
        var current = future;
        if (current != null) current.cancel(false);
    }
}
