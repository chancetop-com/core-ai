package ai.core.cli.appserver;

import ai.core.cli.CliAppHelper;

/** Stops the scheduler and live sessions before releasing the engine-wide resources. */
final class AppServerShutdown {
    static void close(EngineScheduler scheduler, EngineSessionRegistry registry, EngineBootstrap bootstrap) {
        try {
            scheduler.close();
            registry.closeAll();
        } finally {
            CliAppHelper.closeShutdownResources(bootstrap.result);
            bootstrap.close();
        }
    }

    private AppServerShutdown() {
    }
}
