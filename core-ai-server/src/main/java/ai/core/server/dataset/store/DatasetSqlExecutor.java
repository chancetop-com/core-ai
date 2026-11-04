package ai.core.server.dataset.store;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stage-3 executor that lets an agent run a read-only query against the dataset views. It connects with a
 * dedicated login role that only has SELECT on the dataset tables, scopes row level security with a random
 * per-query token, and caps both time and rows. The role is a login role (not {@code SET ROLE}) because
 * {@code set_config('role', 'none')} would otherwise restore the privileged session user.
 *
 * @author stephen
 */
public class DatasetSqlExecutor {
    private static final int STATEMENT_TIMEOUT_SECONDS = 5;
    private static final int MAX_ROWS = 1000;
    private static final String TOKEN_SETTING = "app.dataset_token";

    static String strip(String sql) {
        var value = sql.strip();
        while (value.endsWith(";")) value = value.substring(0, value.length() - 1).strip();
        if (value.indexOf(';') >= 0) throw new IllegalArgumentException("only a single statement is allowed");
        if (value.isEmpty()) throw new IllegalArgumentException("sql is required");
        return value;
    }

    private static Object value(Object value) {
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        if (value instanceof Timestamp time) return time.toInstant().toString();
        if (value instanceof ZonedDateTime || value instanceof BigDecimal) return value.toString();
        return value;
    }

    private static String quoteIdentifier(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private final String url;
    private final String adminUser;
    private final String adminPassword;
    private final String readerUser;
    private final String readerPassword;

    public DatasetSqlExecutor(String url, String adminUser, String adminPassword, String readerUser, String readerPassword) {
        this.url = url;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.readerUser = readerUser;
        this.readerPassword = readerPassword;
    }

    public void initialize() {
        try (Connection connection = adminConnection()) {
            executeAll(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize dataset sql executor", e);
        }
    }

    private void executeAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (var ddl : setupStatements()) {
                statement.execute(ddl);
            }
        }
    }

    private List<String> setupStatements() {
        var reader = quoteIdentifier(readerUser);
        var readerLiteral = quoteLiteral(readerUser);
        var passwordLiteral = quoteLiteral(readerPassword);
        return List.of(
                "DO $$ BEGIN "
                        + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = " + readerLiteral + ") THEN "
                        + "EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', " + readerLiteral + ", " + passwordLiteral + "); "
                        + "ELSE EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', " + readerLiteral + ", " + passwordLiteral + "); "
                        + "END IF; END $$",
                "GRANT SELECT ON dataset_records TO " + reader,
                "GRANT SELECT ON dataset_sql_grants TO " + reader,
                "GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + reader,
                "ALTER TABLE dataset_records ENABLE ROW LEVEL SECURITY",
                "DROP POLICY IF EXISTS dataset_reader_scope ON dataset_records",
                "CREATE POLICY dataset_reader_scope ON dataset_records FOR SELECT TO " + reader
                        + " USING (dataset_id IN (SELECT g.dataset_id FROM dataset_sql_grants g"
                        + " WHERE g.token = current_setting('" + TOKEN_SETTING + "', true)))",
                "ALTER TABLE dataset_sql_grants ENABLE ROW LEVEL SECURITY",
                "DROP POLICY IF EXISTS dataset_grants_scope ON dataset_sql_grants",
                "CREATE POLICY dataset_grants_scope ON dataset_sql_grants FOR SELECT TO " + reader
                        + " USING (token = current_setting('" + TOKEN_SETTING + "', true))",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + reader);
    }

    public Result execute(String sql, List<String> allowedDatasetIds) {
        var token = UUID.randomUUID().toString();
        grant(token, allowedDatasetIds);
        try (Connection connection = readerConnection()) {
            connection.setAutoCommit(false);
            return run(connection, sql, token);
        } catch (SQLException e) {
            throw new IllegalStateException("dataset sql execution failed: " + e.getMessage(), e);
        }
    }

    private Result run(Connection connection, String sql, String token) throws SQLException {
        try (Statement setup = connection.createStatement()) {
            setup.execute("SET LOCAL statement_timeout = " + (STATEMENT_TIMEOUT_SECONDS * 1000));
            setup.execute("SET LOCAL " + TOKEN_SETTING + " = " + quoteLiteral(token));
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM (" + strip(sql) + ") query")) {
            statement.setMaxRows(MAX_ROWS + 1);
            statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            return query(statement);
        } finally {
            connection.rollback();
        }
    }

    private Result query(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return read(resultSet);
        }
    }

    private Result read(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        var columns = new ArrayList<String>(meta.getColumnCount());
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }
        var rows = new ArrayList<List<Object>>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
            var row = new ArrayList<Object>(columns.size());
            for (int i = 1; i <= columns.size(); i++) {
                row.add(value(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new Result(columns, rows, truncated);
    }

    private void grant(String token, List<String> datasetIds) {
        try (Connection connection = adminConnection()) {
            insertGrants(connection, token, datasetIds);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to grant dataset sql scope", e);
        }
    }

    private void insertGrants(Connection connection, String token, List<String> datasetIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dataset_sql_grants (token, dataset_id, created_at) VALUES (?, ?, now())")) {
            for (var datasetId : datasetIds) {
                statement.setString(1, token);
                statement.setString(2, datasetId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        cleanup(connection);
    }

    private void cleanup(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM dataset_sql_grants WHERE created_at < now() - interval '1 hour'");
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(url, adminUser, adminPassword);
    }

    private Connection readerConnection() throws SQLException {
        return DriverManager.getConnection(url, readerUser, readerPassword);
    }

    public record Result(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }
}
