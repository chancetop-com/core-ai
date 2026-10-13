package ai.core.server.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Graceful shutdown of the run pools. Extracted from {@link AgentRunner} (file-size budget); the order
 * is the point — in-flight runs keep submitting timeout and sandbox-release tasks to the scheduler, so
 * stopping it first would make those submissions fail and mark otherwise-fine runs FAILED.
 *
 * @author stephen
 */
public final class RunPools {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunPools.class);
    private static final int AWAIT_SECONDS = 5;

    public static void awaitShutdown(ExecutorService runs, ScheduledExecutorService scheduler) {
        runs.shutdown();
        try {
            if (!runs.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                runs.shutdownNow();
            }
        } catch (InterruptedException e) {
            runs.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("interrupted while awaiting run pool shutdown, forcing it", e);
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private RunPools() {
    }
}
