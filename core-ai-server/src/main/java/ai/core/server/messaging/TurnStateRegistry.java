package ai.core.server.messaging;

import ai.core.api.server.session.AgentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Cross-pod source of truth for "is a turn currently executing on this session".
 *
 * The owner pod writes a lease at turn start and deletes it at turn end (complete/error/cancel);
 * validity follows the owner pod's heartbeat. The local map keeps the turn's user and liveness
 * probe, and the heartbeat clears a turn whose thread died instead of pinning it as running.
 *
 * @author xander
 */
public class TurnStateRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnStateRegistry.class);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(30);

    private final TurnStateStore store;
    private final String podId;
    // sessionId -> which user owns that turn and "is that turn still executing here". The heartbeat
    // consults the supplier before renewing, so a turn whose thread died takes the lease-expiry path
    // instead of being renewed forever.
    private final Map<String, TurnEntry> runningTurns = new ConcurrentHashMap<>();
    private volatile boolean heartbeatActive = true;
    private Thread heartbeatThread;

    public TurnStateRegistry(TurnStateStore store, String podId) {
        this.store = store;
        this.podId = podId;
    }

    public void markRunning(String sessionId) {
        markRunning(sessionId, null, null);
    }

    /** A turn whose owner is unknown: status is tracked, per-user accounting is skipped. */
    public void markRunning(String sessionId, BooleanSupplier turnLiveness) {
        markRunning(sessionId, null, turnLiveness);
    }

    /**
     * @param userId        owner of the turn, so the per-user in-flight count can be kept (null = uncounted)
     * @param turnLiveness  answers "is this turn still executing on this pod"; the heartbeat stops
     *                      renewing (and clears) as soon as it says no. Null means always alive,
     *                      which only leaves the store's validity as protection — pass a real check where possible.
     */
    public void markRunning(String sessionId, String userId, BooleanSupplier turnLiveness) {
        runningTurns.put(sessionId, new TurnEntry(userId, turnLiveness != null ? turnLiveness : () -> true));
        store.markRunning(sessionId, userId, podId);
    }

    public void clear(String sessionId) {
        var entry = runningTurns.remove(sessionId);
        store.clear(sessionId, entry != null ? entry.userId() : null, podId);
    }

    /**
     * Turns this user currently has executing across the fleet. Used for the per-user concurrency limit:
     * the caller is refused a new message rather than having its running turns interrupted.
     */
    public int inFlightTurns(String userId) {
        return store.inFlightTurns(userId);
    }

    /** Turns executing on this pod right now — the pod-local signal used by entry admission. */
    public int runningCount() {
        return runningTurns.size();
    }

    public TurnLiveness liveness(String sessionId) {
        return store.liveness(sessionId);
    }

    /**
     * Session event listener that mirrors the turn lifecycle into the store. Register it
     * before the SSE bridge so the lease is written before the RUNNING event reaches
     * other pods.
     */
    public AgentEventListener listener(String sessionId) {
        return listener(sessionId, null, null);
    }

    public AgentEventListener listener(String sessionId, String userId, BooleanSupplier turnLiveness) {
        return listener(sessionId, userId, turnLiveness, null);
    }

    /**
     * @param pendingWork answers "does this session still have background work queued or running".
     *                    A turn whose deferred work outlives it keeps the state (and its renewals) alive,
     *                    so the workspace lease and the idle sleeper see the session as busy.
     */
    public AgentEventListener listener(String sessionId, String userId, BooleanSupplier turnLiveness,
                                       BooleanSupplier pendingWork) {
        return new TurnStateListener(this, sessionId, userId, turnLiveness, pendingWork);
    }

    void renewAll() {
        for (var entry : runningTurns.entrySet()) {
            var sessionId = entry.getKey();
            if (!entry.getValue().liveness().getAsBoolean()) {
                // The turn behind this lease is gone but nothing cleared it — renewing would pin the
                // session to "running" for the lifetime of the pod. Clear it so status flips to idle
                // and the frontend re-syncs from history.
                LOGGER.warn("turn is no longer executing but its state was never cleared, clearing now, sessionId={}", sessionId);
                clear(sessionId);
                continue;
            }
            store.renew(sessionId, entry.getValue().userId());
        }
    }

    public void start() {
        heartbeatThread = Thread.ofVirtual().name("turn-state-heartbeat").start(this::heartbeatLoop);
    }

    public void stop() {
        heartbeatActive = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        // Turns die with this pod — delete their leases so status flips to idle immediately
        // instead of waiting for the pod heartbeat to expire.
        for (var sessionId : List.copyOf(runningTurns.keySet())) {
            clear(sessionId);
        }
    }

    private void heartbeatLoop() {
        while (heartbeatActive) {
            try {
                Thread.sleep(RENEW_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (heartbeatActive) {
                renewAll();
            }
        }
    }

    /** One executing turn: who owns it, and how to tell whether its thread is still alive. */
    private record TurnEntry(String userId, BooleanSupplier liveness) { }

    public enum TurnLiveness {
        RUNNING, NOT_RUNNING, UNKNOWN
    }
}
