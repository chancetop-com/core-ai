package ai.core.server;

import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fails startup when the trace datasource is unreachable or its schema is missing, instead of letting the
 * first trace write discover it. A dedicated trace database is migrated separately, so an empty one is a
 * configuration mistake rather than a transient error.
 *
 * <p>The connection budget check runs on every startup: the main and trace pools are sized independently,
 * and their sum per replica must stay below the server's max_connections.
 *
 * @author stephen
 */
public class TraceDbCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceDbCheck.class);
    private static final List<String> TRACE_TABLES = List.of(
        "app.traces",
        "app.spans",
        "app.trace_payloads",
        "app.trace_daily_stats",
        "app.analytics_daily_stats"
    );

    @Inject
    @Named(TraceDbModule.NAME)
    Database database;

    private final boolean schemaCheck;
    private final int plannedConnections;
    private final boolean failOnBudgetExceeded;

    public TraceDbCheck(boolean schemaCheck, int plannedConnections, boolean failOnBudgetExceeded) {
        this.schemaCheck = schemaCheck;
        this.plannedConnections = plannedConnections;
        this.failOnBudgetExceeded = failOnBudgetExceeded;
    }

    public void verify() {
        verifyConnectivity();
        if (schemaCheck) verifySchema();
        verifyConnectionBudget();
    }

    private void verifyConnectivity() {
        PgStartupRow row;
        try {
            row = database.selectOne("SELECT 1 AS ok", PgStartupRow.class).orElse(null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("trace database is not reachable, check sys.trace.db.url", e);
        }
        if (row == null || row.ok == null || row.ok != 1) {
            throw new IllegalStateException("trace database is not reachable");
        }
        LOGGER.info("trace database verified");
    }

    private void verifySchema() {
        for (var table : TRACE_TABLES) {
            try {
                database.selectOne("SELECT 1 AS ok FROM " + table + " WHERE false", PgStartupRow.class);
            } catch (RuntimeException e) {
                throw new IllegalStateException("trace database is missing " + table
                    + ", run Flyway against sys.trace.db.url before starting the server", e);
            }
        }
        LOGGER.info("trace database schema verified, tables={}", TRACE_TABLES.size());
    }

    private void verifyConnectionBudget() {
        var maxConnections = database.selectOne("SELECT current_setting('max_connections') AS value", PgSettingRow.class)
            .map(row -> row.value)
            .orElse(null);
        if (maxConnections == null || maxConnections.isBlank()) {
            LOGGER.warn("max_connections is not available, connection budget check skipped");
            return;
        }
        int limit;
        try {
            limit = Integer.parseInt(maxConnections);
        } catch (NumberFormatException e) {
            LOGGER.warn("unexpected max_connections value, value={}, connection budget check skipped", maxConnections);
            return;
        }
        if (plannedConnections <= limit) {
            LOGGER.info("trace connection budget verified, planned={}, maxConnections={}", plannedConnections, limit);
            return;
        }
        var message = "trace connection budget exceeded, planned=" + plannedConnections + ", maxConnections=" + limit;
        if (failOnBudgetExceeded) throw new IllegalStateException(message);
        LOGGER.warn(message);
    }
}
