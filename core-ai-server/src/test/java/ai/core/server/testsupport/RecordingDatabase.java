package ai.core.server.testsupport;

import core.framework.db.Database;
import core.framework.db.Transaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Database double that records every statement and fails when the number of bound parameters does not
 * match the number of placeholders. Screens the failure mode where a rarely executed statement only
 * breaks when it finally runs.
 *
 * @author stephen
 */
public final class RecordingDatabase implements Database {
    private static void verify(String sql, Object[] params) {
        long placeholders = sql.chars().filter(ch -> ch == '?').count();
        assertEquals(placeholders, params.length, "parameter count mismatch, sql=" + sql);
    }

    private final List<String> statements = new ArrayList<>();

    public int statementCount() {
        return statements.size();
    }

    public List<String> statements() {
        return statements;
    }

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
        Arrays.fill(results, 0);
        return results;
    }

    @Override
    public Transaction beginTransaction() {
        throw new UnsupportedOperationException("read/write only in this test double");
    }
}
