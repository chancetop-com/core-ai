package ai.core.cli.appserver;

import ai.core.cli.CliAppHelper;
import ai.core.cli.trace.HttpTraceUploader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Stops the scheduler and live sessions before releasing the engine-wide resources. */
final class AppServerShutdown {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerShutdown.class);
    // Live trace batches queued by the last turn are best-effort; never hold the shutdown path longer than this.
    private static final Duration TRACE_FLUSH_TIMEOUT = Duration.ofSeconds(3);

    static void close(EngineScheduler scheduler, EngineSessionRegistry registry, EngineBootstrap bootstrap) {
        try {
            scheduler.close();
            registry.closeAll();
        } catch (RuntimeException | Error e) {
            release(bootstrap);
            throw e;
        }
        release(bootstrap);
    }

    private static void release(EngineBootstrap bootstrap) {
        try {
            CliAppHelper.closeShutdownResources(bootstrap.result);
            bootstrap.close();
        } catch (RuntimeException | Error e) {
            flushTraceUploads();
            throw e;
        }
        flushTraceUploads();
    }

    private static void flushTraceUploads() {
        try {
            if (!HttpTraceUploader.flush(TRACE_FLUSH_TIMEOUT)) {
                LOGGER.warn("live trace upload flush timed out during shutdown");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("live trace upload flush failed during shutdown", e);
        }
    }

    private AppServerShutdown() {
    }
}
