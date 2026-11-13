package ai.core.server.runtime;

import core.framework.db.Database;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class PgRuntimeSampleSqlParamsTest {
    private final Database database = mock(Database.class);

    // varargs arrive either as one array or as separate arguments, depending on the call site
    private static Object[] bound(Object[] invocationArgs, int from) {
        var rest = Arrays.copyOfRange(invocationArgs, from, invocationArgs.length);
        if (rest.length == 1 && rest[0] instanceof Object[] array) return array;
        return rest;
    }

    private static void assertParamCount(String sql, Object[] params) {
        long placeholders = sql.chars().filter(ch -> ch == '?').count();
        assertEquals(placeholders, params.length, "parameter count mismatch, sql=" + sql);
    }

    @Test
    void statements() {
        when(database.select(anyString(), any(), any())).thenAnswer(invocation -> {
            assertParamCount(invocation.getArgument(0), bound(invocation.getArguments(), 2));
            return List.of();
        });
        when(database.selectOne(anyString(), any(), any())).thenReturn(Optional.empty());
        when(database.execute(anyString(), any())).thenAnswer(invocation -> {
            assertParamCount(invocation.getArgument(0), bound(invocation.getArguments(), 1));
            return 0;
        });
        when(database.batchExecute(anyString(), any())).thenReturn(new int[0]);

        var store = new PgRuntimeSampleStore();
        store.database = database;

        var sample = new RuntimeSample();
        sample.id = "s1";
        store.insert(sample);
        store.bulkInsert(List.of(sample, sample));
        store.range(ZonedDateTime.parse("2026-11-01T00:00:00Z"), ZonedDateTime.parse("2026-11-02T00:00:00Z"), 10);
    }
}
