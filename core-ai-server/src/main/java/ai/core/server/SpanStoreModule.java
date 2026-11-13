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
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SpanStore.class, bind(PgSpanStore.class));
            boolean copyLegacySpans = !"skip".equals(property("sys.trace.span.migration").orElse("copy"));
            var migration = bind(SpanMigrationService.class);
            onStartup(() -> migration.migrate(copyLegacySpans));
        } else {
            bind(SpanStore.class, bind(MongoSpanStore.class));
        }
    }
}
