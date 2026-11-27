package ai.core.server;

import ai.core.server.pg.StoreMode;
import ai.core.server.trace.PgLlmModelMetricsRow;
import ai.core.server.trace.PgLlmThroughputRow;
import ai.core.server.trace.PgMigrationProgressRow;
import ai.core.server.trace.PgSpanMigrationLockRow;
import ai.core.server.trace.PgSpanMigrationMarkerRow;
import ai.core.server.trace.PgSpanRow;
import ai.core.server.trace.PgSpanTokenTotalsRow;
import ai.core.server.trace.PgTraceAggregateRow;
import ai.core.server.trace.PgTraceCountRow;
import ai.core.server.trace.PgTraceDailyUsageRow;
import ai.core.server.trace.PgTraceDimensionTrendRow;
import ai.core.server.trace.PgTraceFacetRow;
import ai.core.server.trace.PgTraceIdRow;
import ai.core.server.trace.PgTraceRollupRow;
import ai.core.server.trace.PgTraceRow;
import ai.core.server.trace.PgTraceStatsRow;
import ai.core.server.trace.PgTraceTraceIdRow;
import ai.core.server.trace.PgTraceTrendRow;
import ai.core.server.trace.service.PgTracePayloadRow;
import ai.core.server.trace.service.PgTraceStorageKeyRow;
import core.framework.db.Database;
import core.framework.module.DBConfig;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Binds the named trace datasource. Trace tables get their own pool even when they share the main
 * database, so a blocked trace query cannot exhaust the connections that serve the rest of the API.
 * Setting {@code sys.trace.db.url} points the same stores at a dedicated PostgreSQL.
 *
 * <p>Must be loaded right after {@link PostgresModule}: the trace stores are created when their store
 * modules bind them, and eager injection would fail without the named bean.
 *
 * <p>core-ng keeps the transaction connection in a static ThreadLocal, so a transaction on one datasource
 * must never call the other. No trace code opens a main-database transaction.
 *
 * @author stephen
 */
public class TraceDbModule extends Module {
    public static final String NAME = "trace";
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceDbModule.class);

    @Override
    protected void initialize() {
        var globalMode = property("sys.store.mode").orElse("mongo");
        var tracePostgres = StoreMode.postgres(property("sys.trace.store.mode").orElse("auto"), globalMode, "sys.trace.store.mode");
        var spanPostgres = StoreMode.postgres(property("sys.trace.span.store.mode").orElse("auto"), globalMode, "sys.trace.span.store.mode");
        if (!tracePostgres && !spanPostgres) return;

        if (!booleanProperty("sys.trace.db.enabled", true)) {
            // rollback lever: keep the code path unchanged but share the main pool again
            registerViews(db());
            bind(Database.class, NAME, bean(Database.class));
            LOGGER.info("trace datasource disabled, trace stores share the main datasource");
            return;
        }

        var url = property("sys.trace.db.url").orElse("");
        var dedicated = !url.isBlank();
        var config = db(NAME);
        config.url(dedicated ? url : requiredProperty("sys.db.url"));
        var user = property("sys.trace.db.user").orElse("");
        config.user(user.isBlank() ? requiredProperty("sys.db.user") : user);
        var password = property("sys.trace.db.password").orElse("");
        config.password(password.isBlank() ? requiredProperty("sys.db.password") : password);
        var poolMin = intProperty("sys.trace.db.pool.min", 1);
        var poolMax = intProperty("sys.trace.db.pool.max", 4);
        var timeoutSeconds = intProperty("sys.trace.db.timeout.seconds", 10);
        config.poolSize(poolMin, poolMax);
        config.timeout(Duration.ofSeconds(timeoutSeconds));
        registerViews(config);
        LOGGER.info("trace datasource configured, dedicated={}, pool={}..{}, timeout={}s",
            dedicated, poolMin, poolMax, timeoutSeconds);

        var check = new TraceDbCheck(booleanProperty("sys.trace.db.schema.check", true),
            dedicated,
            intProperty("sys.db.pool.max", 50),
            poolMax,
            intProperty("sys.trace.db.conn.budget.replicas", 2),
            intProperty("sys.trace.db.conn.budget.reserve", 10),
            booleanProperty("sys.trace.db.conn.budget.fail", false));
        bind(check);
        onStartup(check::verify);
    }

    private void registerViews(DBConfig config) {
        config.view(PgStartupRow.class);
        config.view(PgSettingRow.class);
        config.view(PgSpanRow.class);
        config.view(PgSpanTokenTotalsRow.class);
        config.view(PgTraceCountRow.class);
        config.view(PgTraceIdRow.class);
        config.view(PgLlmThroughputRow.class);
        config.view(PgLlmModelMetricsRow.class);
        config.view(PgSpanMigrationLockRow.class);
        config.view(PgSpanMigrationMarkerRow.class);
        config.view(PgMigrationProgressRow.class);
        config.view(PgTraceRow.class);
        config.view(PgTraceFacetRow.class);
        config.view(PgTraceAggregateRow.class);
        config.view(PgTraceTraceIdRow.class);
        config.view(PgTraceRollupRow.class);
        config.view(PgTraceStatsRow.class);
        config.view(PgTraceTrendRow.class);
        config.view(PgTraceDimensionTrendRow.class);
        config.view(PgTraceDailyUsageRow.class);
        config.view(PgTracePayloadRow.class);
        config.view(PgTraceStorageKeyRow.class);
    }

    private int intProperty(String key, int defaultValue) {
        var value = property(key).orElse(null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            LOGGER.warn("invalid int property {}, using {}", key, defaultValue);
            return defaultValue;
        }
    }

    private boolean booleanProperty(String key, boolean defaultValue) {
        var value = property(key).orElse(null);
        if (value == null || value.isBlank()) return defaultValue;
        return Boolean.parseBoolean(value.strip());
    }
}
