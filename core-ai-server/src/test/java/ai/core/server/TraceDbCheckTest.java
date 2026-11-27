package ai.core.server;

import core.framework.db.Database;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class TraceDbCheckTest {
    private final Database database = mock(Database.class);

    @Test
    void passesWhenSchemaAndBudgetFit() {
        when(database.selectOne("SELECT 1 AS ok", PgStartupRow.class)).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("app."), eq(PgStartupRow.class))).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("max_connections"), eq(PgSettingRow.class)))
            .thenReturn(Optional.of(setting("100")));

        var check = check(true, 100, false);

        assertDoesNotThrow(check::verify);
    }

    @Test
    void failsFastWhenTraceTableIsMissing() {
        when(database.selectOne("SELECT 1 AS ok", PgStartupRow.class)).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("app.traces"), eq(PgStartupRow.class))).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("app.spans"), eq(PgStartupRow.class)))
            .thenThrow(new RuntimeException("relation does not exist"));
        when(database.selectOne(contains("max_connections"), eq(PgSettingRow.class)))
            .thenReturn(Optional.of(setting("300")));

        var check = check(true, 100, false);

        var error = assertThrows(IllegalStateException.class, check::verify);
        assertTrue(error.getMessage().contains("app.spans"));
    }

    @Test
    void budgetOverrunOnlyWarnsByDefault() {
        when(database.selectOne("SELECT 1 AS ok", PgStartupRow.class)).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("max_connections"), eq(PgSettingRow.class)))
            .thenReturn(Optional.of(setting("100")));

        var check = check(false, 118, false);

        assertDoesNotThrow(check::verify);
    }

    @Test
    void budgetOverrunFailsWhenConfigured() {
        when(database.selectOne("SELECT 1 AS ok", PgStartupRow.class)).thenReturn(Optional.of(ok()));
        when(database.selectOne(contains("max_connections"), eq(PgSettingRow.class)))
            .thenReturn(Optional.of(setting("100")));

        var check = check(false, 118, true);

        var error = assertThrows(IllegalStateException.class, check::verify);
        assertTrue(error.getMessage().contains("connection budget"));
    }

    private TraceDbCheck check(boolean schemaCheck, int planned, boolean fail) {
        var check = new TraceDbCheck(schemaCheck, planned, fail);
        check.database = database;
        return check;
    }

    private PgStartupRow ok() {
        var row = new PgStartupRow();
        row.ok = 1;
        return row;
    }

    private PgSettingRow setting(String value) {
        var row = new PgSettingRow();
        row.value = value;
        return row;
    }
}
