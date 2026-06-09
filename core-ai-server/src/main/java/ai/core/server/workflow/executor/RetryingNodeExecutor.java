package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an executor with synchronous, in-place retry for transient failures (Dify-style): on a retryable
 * {@link NodeOutcome.Fail} it waits {@code retry_interval_ms} and re-runs the inner executor, up to
 * {@code max_retries} times, then returns the last outcome. A success or a non-retryable failure returns
 * immediately. Both knobs come from the node config (defaults 3 / 1000ms; max_retries capped so a typo cannot
 * pin a worker thread). The engine never sees the intermediate failures — only the final outcome — so the
 * planner, journal and drive loop stay unchanged.
 *
 * @author Xander
 */
public class RetryingNodeExecutor implements NodeExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetryingNodeExecutor.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 1000;
    private static final int MAX_ALLOWED_RETRIES = 10;   // guard: a config typo must not pin this worker thread forever

    private final NodeExecutor inner;

    public RetryingNodeExecutor(NodeExecutor inner) {
        this.inner = inner;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        int maxRetries = readMaxRetries(ctx);
        long interval = readInterval(ctx);
        NodeOutcome outcome = inner.execute(ctx);
        int attempt = 0;
        while (outcome instanceof NodeOutcome.Fail fail && fail.retryable() && attempt < maxRetries) {
            attempt++;
            LOGGER.warn("node {} failed (retryable), retry {}/{} after {}ms: {}",
                ctx.node().id(), attempt, maxRetries, interval, fail.error());
            if (!sleep(interval)) {
                return outcome;   // interrupted (cancel / shutdown) — stop retrying, surface the last failure
            }
            outcome = inner.execute(ctx);
        }
        return outcome;
    }

    private boolean sleep(long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int readMaxRetries(NodeContext ctx) {
        int value = intConfig(ctx, "max_retries", DEFAULT_MAX_RETRIES);
        return Math.max(0, Math.min(value, MAX_ALLOWED_RETRIES));
    }

    private long readInterval(NodeContext ctx) {
        return Math.max(0, intConfig(ctx, "retry_interval_ms", (int) DEFAULT_RETRY_INTERVAL_MS));
    }

    private int intConfig(NodeContext ctx, String key, int defaultValue) {
        Object value = ctx.node().config().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
