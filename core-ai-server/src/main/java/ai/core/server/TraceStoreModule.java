package ai.core.server;

import ai.core.server.pg.StoreMode;
import ai.core.server.trace.AnalyticsStatsStore;
import ai.core.server.trace.MongoAnalyticsStatsStore;
import ai.core.server.trace.MongoTraceDailyStatsStore;
import ai.core.server.trace.MongoTraceStore;
import ai.core.server.trace.PgAnalyticsStatsStore;
import ai.core.server.trace.PgTraceAnalytics;
import ai.core.server.trace.PgTraceDailyStatsStore;
import ai.core.server.trace.PgTraceStore;
import ai.core.server.trace.TraceDailyStatsStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.service.MongoTracePayloadRepository;
import ai.core.server.trace.service.PgTracePayloadRepository;
import ai.core.server.trace.service.TracePayloadRepository;
import core.framework.module.Module;

/**
 * Binds the trace stores before any module that injects them: analytics, API users, memory, For You and
 * the trace module itself all resolve their fields when they are loaded, so the bindings cannot live in
 * TraceModule.
 *
 * <p>Follows the same switch shape as {@link SpanStoreModule}: {@code sys.trace.store.mode} is auto,
 * mongo or pg, and auto follows {@code sys.store.mode}. Traces and the two rollup tables cannot be
 * split across stores, otherwise the daily job would write rows the analytics screen never reads.
 *
 * @author stephen
 */
public class TraceStoreModule extends Module {
    @Override
    protected void initialize() {
        if (postgresStore()) {
            property("sys.trace.migration");
            bind(PgTraceAnalytics.class);
            bind(TraceStore.class, bind(PgTraceStore.class));
            bind(TraceDailyStatsStore.class, bind(PgTraceDailyStatsStore.class));
            bind(AnalyticsStatsStore.class, bind(PgAnalyticsStatsStore.class));
            bind(TracePayloadRepository.class, bind(PgTracePayloadRepository.class));
        } else {
            bind(TraceStore.class, bind(MongoTraceStore.class));
            bind(TraceDailyStatsStore.class, bind(MongoTraceDailyStatsStore.class));
            bind(AnalyticsStatsStore.class, bind(MongoAnalyticsStatsStore.class));
            bind(TracePayloadRepository.class, bind(MongoTracePayloadRepository.class));
        }
    }

    private boolean postgresStore() {
        return StoreMode.postgres(property("sys.trace.store.mode").orElse("auto"),
            property("sys.store.mode").orElse("mongo"), "sys.trace.store.mode");
    }
}
