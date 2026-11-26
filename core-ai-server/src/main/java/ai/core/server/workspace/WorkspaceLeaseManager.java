package ai.core.server.workspace;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

/**
 * The single-writer gate of a workspace. A turn lease is held by the session whose turn executes
 * tools; a maintenance lease is held by the sleeper. A lease is replaceable only when it expired or
 * its session stopped running a turn, so an abandoned row never pins a workspace, while a turn that
 * is still executing keeps it.
 *
 * @author stephen
 */
class WorkspaceLeaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceLeaseManager.class);
    private static final long RETRY_MILLIS = 250;
    private static final String KIND_TURN = "turn";
    private static final String KIND_MAINTENANCE = "maintenance";

    static boolean active(Predicate<String> sessionBusy, String sessionId) {
        if (sessionId == null) return false;
        return sessionBusy == null || sessionBusy.test(sessionId);
    }

    static void sleepUntilRetry() {
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException(WorkspaceException.BUSY, "interrupted while waiting for the workspace lease", e);
        }
    }

    private final WorkspaceLeaseStore leases;
    private final WorkspaceConfig config;
    private final ScheduledExecutorService scheduler;

    @SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
    WorkspaceLeaseManager(WorkspaceLeaseStore leases, WorkspaceConfig config) {
        this.leases = leases;
        this.config = config;
        this.scheduler = leases == null ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "workspace-lease");
            thread.setDaemon(true);
            return thread;
        });
    }

    WorkspaceLeaseRecord current(String workspaceId) {
        return leases.get(workspaceId).orElse(null);
    }

    /** @return the lease this session now holds, or null when another running turn owns the workspace */
    WorkspaceLeaseRecord acquireTurn(String workspaceId, String sessionId, long runtimeGeneration,
                                     Predicate<String> sessionBusy) {
        var now = ZonedDateTime.now();
        var existing = leases.get(workspaceId).orElse(null);
        if (existing == null) {
            var lease = newLease(workspaceId, sessionId, KIND_TURN, sessionId, runtimeGeneration, now);
            if (leases.insert(lease)) return lease;
            existing = leases.get(workspaceId).orElse(null);
            if (existing == null) return null;
        }
        if (sessionId.equals(existing.sessionId)) {
            return leases.renew(workspaceId, existing.leaseToken, now.plusSeconds(config.leaseTtlSeconds))
                    ? existing : null;
        }
        if (!stealable(existing, now, sessionBusy)) return null;
        var replacement = newLease(workspaceId, sessionId, KIND_TURN, sessionId, runtimeGeneration, now);
        return leases.replace(workspaceId, existing.leaseToken, replacement) ? replacement : null;
    }

    /** @return the sleeper's short lease, or null while a turn or another sleeper owns the workspace */
    WorkspaceLeaseRecord tryAcquireMaintenance(String workspaceId, long runtimeGeneration, Predicate<String> sessionBusy) {
        var now = ZonedDateTime.now();
        var existing = leases.get(workspaceId).orElse(null);
        var lease = newLease(workspaceId, "sleeper", KIND_MAINTENANCE, null, runtimeGeneration, now);
        lease.expiresAt = now.plusSeconds(Math.min(config.leaseTtlSeconds, 120));
        if (existing == null) return leases.insert(lease) ? lease : null;
        if (!existing.expired(now) && (KIND_MAINTENANCE.equals(existing.leaseKind) || active(sessionBusy, existing.sessionId))) {
            return null;
        }
        return leases.replace(workspaceId, existing.leaseToken, lease) ? lease : null;
    }

    WorkspaceLeaseHeartbeat heartbeat(String workspaceId, String token, Predicate<String> sessionBusy) {
        if (scheduler == null || token == null) return null;
        return WorkspaceLeaseHeartbeat.start(scheduler, leases, workspaceId, token, config.leaseTtlSeconds,
                () -> active(sessionBusy, holderSession(workspaceId, token)));
    }

    void release(String workspaceId, String token) {
        if (leases == null || token == null) return;
        try {
            leases.release(workspaceId, token);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release workspace lease, workspace={}", workspaceId, e);
        }
    }

    void releaseByWorkspace(String workspaceId) {
        if (leases == null) return;
        try {
            leases.releaseByWorkspace(workspaceId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release workspace leases, workspace={}", workspaceId, e);
        }
    }

    private String holderSession(String workspaceId, String token) {
        return leases.get(workspaceId)
                .filter(lease -> token.equals(lease.leaseToken))
                .map(lease -> lease.sessionId)
                .orElse(null);
    }

    private boolean stealable(WorkspaceLeaseRecord lease, ZonedDateTime now, Predicate<String> sessionBusy) {
        if (lease.expired(now)) return true;
        if (KIND_MAINTENANCE.equals(lease.leaseKind)) return false;
        return !active(sessionBusy, lease.sessionId);
    }

    private WorkspaceLeaseRecord newLease(String workspaceId, String holderId, String kind, String sessionId,
                                          long runtimeGeneration, ZonedDateTime now) {
        var lease = new WorkspaceLeaseRecord();
        lease.workspaceId = workspaceId;
        lease.holderId = holderId;
        lease.leaseToken = UUID.randomUUID().toString();
        lease.leaseKind = kind;
        lease.sessionId = sessionId;
        lease.runtimeGeneration = runtimeGeneration;
        lease.expiresAt = now.plusSeconds(config.leaseTtlSeconds);
        lease.renewedAt = now;
        return lease;
    }
}
