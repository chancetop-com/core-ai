package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.LongSupplier;

/**
 * Engine intake barrier used while a change is applied.
 *
 * The desktop calls {@code engine/quiesce} before restarting/switching an engine. New turns and
 * session creation are rejected while the barrier is raised, and the caller pauses the scheduler so
 * no new scheduled run is claimed. Accepted turns are allowed to drain; a timeout returns
 * {@code acquired=false} and restores intake instead of killing normal work.
 */
final class EngineQuiesce {
    private static final int DEFAULT_TIMEOUT_MS = 3_000;
    private static final long POLL_MS = 50;

    private static ObjectNode result(boolean acquired, long active) {
        var node = Params.object();
        node.put("acquired", acquired);
        node.put("active", active);
        return node;
    }

    private volatile boolean quiescing;

    void requireIdle(String action) {
        if (!quiescing) return;
        throw RpcException.business("ENGINE_QUIESCING",
                "the engine is applying a change; retry " + action + " shortly", "retryable", "true");
    }

    ObjectNode quiesce(ObjectNode params, LongSupplier activeSessions) {
        int timeoutMs = Math.max(100, Math.min(Params.intParam(params, "timeoutMs", DEFAULT_TIMEOUT_MS), 30_000));
        quiescing = true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long active = activeSessions.getAsLong();
            if (active == 0) return result(true, 0);
            if (System.currentTimeMillis() >= deadline) {
                quiescing = false;
                return result(false, active);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                quiescing = false;
                return result(false, -1);
            }
        }
    }

    ObjectNode resumeIntake(ObjectNode params) {
        quiescing = false;
        return result(true, 0).put("resumed", true);
    }
}
