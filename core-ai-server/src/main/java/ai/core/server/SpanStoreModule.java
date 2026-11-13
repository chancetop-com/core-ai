package ai.core.server;

import ai.core.server.trace.MongoSpanStore;
import ai.core.server.trace.PgSpanStore;
import ai.core.server.trace.SpanMigrationService;
import ai.core.server.trace.SpanStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SpanStoreModule extends Module {
    @Override
    protected void initialize() {
        registerStore(property("sys.trace.span.migration").orElse("copy"));
    }

    private void registerStore(String spanMigration) {
        if (postgresStore()) {
            bind(SpanStore.class, bind(PgSpanStore.class));
            boolean copyLegacySpans = !"skip".equals(spanMigration);
            var migration = bind(SpanMigrationService.class);
            onStartup(() -> migration.start(copyLegacySpans));
        } else {
            bind(SpanStore.class, bind(MongoSpanStore.class));
        }
    }

    private boolean postgresStore() {
        var globalMode = property("sys.store.mode").orElse("mongo");
        return switch (property("sys.trace.span.store.mode").orElse("auto")) {
            case "auto" -> "pg".equals(globalMode);
            case "mongo" -> false;
            case "pg" -> {
                if (!"pg".equals(globalMode)) {
                    throw new IllegalArgumentException("sys.trace.span.store.mode=pg requires sys.store.mode=pg");
                }
                yield true;
            }
            default -> throw new IllegalArgumentException("sys.trace.span.store.mode must be auto, mongo, or pg");
        };
    }
}
