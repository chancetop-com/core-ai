package ai.core.server.runtime;

import core.framework.db.Database;
import core.framework.db.Transaction;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class PgRuntimeSampleSqlParamsTest {
    @Test
    void statements() {
        var database = new RecordingDatabase();
        var store = new PgRuntimeSampleStore();
        store.database = database;

        var sample = new RuntimeSample();
        sample.id = "s1";
        store.insert(sample);
        store.bulkInsert(List.of(sample, sample));
        store.range(ZonedDateTime.parse("2026-11-01T00:00:00Z"), ZonedDateTime.parse("2026-11-02T00:00:00Z"), 10);

        assertEquals(3, database.statements.size());
    }

    private static final class RecordingDatabase implements Database {
        private static void verify(String sql, Object[] params) {
            long placeholders = sql.chars().filter(ch -> ch == '?').count();
            assertEquals(placeholders, params.length, "parameter count mismatch, sql=" + sql);
        }

        private final List<String> statements = new ArrayList<>();

        @Override
        public <T> List<T> select(String sql, Class<T> viewClass, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return List.of();
        }

        @Override
        public <T> Optional<T> selectOne(String sql, Class<T> viewClass, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return Optional.empty();
        }

        @Override
        public int execute(String sql, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return 0;
        }

        @Override
        public int[] batchExecute(String sql, List<Object[]> params) {
            for (var row : params) verify(sql, row);
            statements.add(sql);
            var results = new int[params.size()];
            java.util.Arrays.fill(results, 0);
            return results;
        }

        @Override
        public Transaction beginTransaction() {
            throw new UnsupportedOperationException("read/write only in this test");
        }
    }
}
