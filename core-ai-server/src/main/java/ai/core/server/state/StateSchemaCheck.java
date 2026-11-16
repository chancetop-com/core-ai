package ai.core.server.state;

import core.framework.db.Database;
import core.framework.inject.Inject;

/**
 * A missing messaging table must abort startup instead of degrading silently: session activity
 * reads would report "never touched", which the idle cleanup would treat as a dead session.
 *
 * @author stephen
 */
public class StateSchemaCheck {
    @Inject
    Database database;

    public void verify() {
        verifyTable("messaging.session_activity");
        verifyTable("messaging.a2a_tasks");
        verifyTable("app.sandbox_bindings");
    }

    private void verifyTable(String table) {
        try {
            database.selectOne("SELECT 1 AS ok FROM " + table + " WHERE false", PgStateCheckRow.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("sys.state.backend=postgres requires Flyway V39, table is missing or unreadable: " + table, e);
        }
    }
}
