package ai.core.server;

import ai.core.server.trace.MongoTraceStore;
import ai.core.server.trace.PgTraceAnalytics;
import ai.core.server.trace.PgTraceStore;
import ai.core.server.trace.TraceMigrationService;
import ai.core.server.trace.TraceStore;
import core.framework.module.Module;

/**
 * Binds the trace store before any module that injects it: analytics, API users, memory and the trace
 * module itself all resolve their fields when they are loaded, so the binding cannot live in TraceModule.
 *
 * <p>Follows the same switch shape as {@link SpanStoreModule}: {@code sys.trace.store.mode} is auto,
 * mongo or pg, and auto follows {@code sys.store.mode}.
 *
 * @author stephen
 */
public class TraceStoreModule extends Module {
    @Override
    protected void initialize() {
        if (postgresStore()) {
            bind(PgTraceAnalytics.class);
            bind(TraceStore.class, bind(PgTraceStore.class));
            var migration = bind(TraceMigrationService.class);
            boolean copyLegacyTraces = !"skip".equals(property("sys.trace.migration").orElse("copy"));
            onStartup(() -> migration.start(copyLegacyTraces));
        } else {
            bind(TraceStore.class, bind(MongoTraceStore.class));
        }
    }

    private boolean postgresStore() {
        var globalMode = property("sys.store.mode").orElse("mongo");
        return switch (property("sys.trace.store.mode").orElse("auto")) {
            case "auto" -> "pg".equals(globalMode);
            case "mongo" -> false;
            case "pg" -> {
                if (!"pg".equals(globalMode)) {
                    throw new IllegalArgumentException("sys.trace.store.mode=pg requires sys.store.mode=pg");
                }
                yield true;
            }
            default -> throw new IllegalArgumentException("sys.trace.store.mode must be auto, mongo, or pg");
        };
    }
}
