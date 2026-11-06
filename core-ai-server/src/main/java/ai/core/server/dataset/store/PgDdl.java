package ai.core.server.dataset.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Runs DDL on a raw JDBC connection. core-ng 9.4.2 sends every statement through {@code EXPLAIN}, which
 * PostgreSQL rejects for DDL, so schema and view changes cannot go through {@code Database.execute}.
 * Closing the connection without a commit rolls the transaction back.
 *
 * @author stephen
 */
public class PgDdl {
    private final String url;
    private final String user;
    private final String password;

    public PgDdl(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public void executeAll(List<String> statements) {
        inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (var sql : statements) statement.execute(sql);
            }
        });
    }

    public void inTransaction(Work work) {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(url, user, password);
            connection.setAutoCommit(false);
            work.run(connection);
            connection.commit();
        } catch (SQLException e) {
            rollback(connection);
            throw new IllegalStateException("failed to execute dataset ddl: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            close(connection);
        }
    }

    private void rollback(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // closing the connection rolls back as well
        }
    }

    private void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // the transaction is already committed or rolled back
        }
    }

    @FunctionalInterface
    public interface Work {
        void run(Connection connection) throws SQLException;
    }
}
