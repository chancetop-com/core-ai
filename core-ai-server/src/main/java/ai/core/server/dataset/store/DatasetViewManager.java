package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaFieldType;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Materializes one read-only view per dataset, so an agent can query typed columns instead of jsonb
 * paths. View names and column names are derived from the dataset id and schema, never from user input
 * verbatim; field names become escaped SQL string literals and identifiers are always double quoted.
 *
 * @author stephen
 */
public class DatasetViewManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetViewManager.class);

    private static final Set<String> RESERVED = Set.of(
            "id", "dataset_id", "agent_id", "run_id", "session_id", "user_id", "data", "run_started_at", "created_at", "updated_at", "created_by", "updated_by",
            "select", "from", "where", "group", "order", "by", "table", "view", "index", "user", "role", "grant", "create", "drop", "insert",
            "update", "delete", "join", "limit", "offset", "and", "or", "not", "null", "default", "primary", "foreign", "check", "case", "when");

    private static final String SYSTEM_COLUMNS = "id, dataset_id, agent_id, run_id, session_id, user_id, data, run_started_at, created_at, updated_at, created_by, updated_by";

    private static final String UPSERT_REGISTRY = "INSERT INTO app.dataset_views (dataset_id, view_name, column_map, schema_hash, updated_at) "
            + "VALUES (?, ?, ?::jsonb, ?, ?) ON CONFLICT (dataset_id) DO UPDATE SET view_name = EXCLUDED.view_name, "
            + "column_map = EXCLUDED.column_map, schema_hash = EXCLUDED.schema_hash, updated_at = EXCLUDED.updated_at";

    public static String viewName(String datasetId) {
        var normalized = datasetId.replace("-", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return "ds_" + truncate(normalized, 60);
    }

    static String normalize(String fieldName) {
        var normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        var nullLike = normalized.isEmpty() || normalized.chars().allMatch(ch -> ch == '_');
        var name = nullLike ? "f" + normalized : normalized;
        if (Character.isDigit(name.charAt(0)) || RESERVED.contains(name)) return truncate("f_" + name, 60);
        return truncate(name, 60);
    }

    static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static String qualifiedView(String viewName) {
        return "dataset." + quoteIdentifier(viewName);
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String uniqueName(String name, Set<String> used) {
        var candidate = name;
        int suffix = 2;
        while (!used.add(candidate)) {
            var tail = "_" + suffix++;
            candidate = truncate(name, 60 - tail.length()) + tail;
        }
        return candidate;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // the hash covers the full select list, so a change to the generated layout (system columns,
    // expressions) rebuilds existing views instead of leaving a stale definition behind
    private static String schemaHash(String selectList) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(selectList.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String expression(Column column) {
        var key = literal(column.sourceName());
        return switch (column.type()) {
            case NUMBER -> "CASE WHEN jsonb_typeof(data -> " + key + ") = 'number' THEN (data ->> " + key + ")::numeric END";
            case BOOLEAN -> "CASE WHEN jsonb_typeof(data -> " + key + ") = 'boolean' THEN (data ->> " + key + ")::boolean END";
            case STRING -> "CASE WHEN jsonb_typeof(data -> " + key + ") = 'string' THEN data ->> " + key + " END";
        };
    }

    @Inject
    PgDdl ddl;

    public void sync(Dataset dataset) {
        if (dataset == null || dataset.id == null) return;
        ddl.inTransaction(connection -> sync(connection, dataset));
    }

    void sync(Connection connection, Dataset dataset) throws SQLException {
        if (dataset == null || dataset.id == null) return;
        var plan = plan(dataset);
        var existing = findRegistry(connection, dataset.id);
        if (existing != null
                && plan.viewName().equals(existing.viewName())
                && plan.schemaHash().equals(existing.schemaHash())
                && viewExists(connection, existing.viewName())) {
            return;
        }

        setLockTimeout(connection);
        try (Statement statement = connection.createStatement()) {
            for (var sql : plan.statements()) statement.execute(sql);
        }
        upsertRegistry(connection, dataset.id, plan);
        LOGGER.info("dataset view synced, datasetId={}, view={}, columns={}", dataset.id, plan.viewName(), plan.columns().size());
    }

    public void drop(String datasetId) {
        if (datasetId == null) return;
        ddl.inTransaction(connection -> {
            var existing = findRegistry(connection, datasetId);
            if (existing == null) return;
            setLockTimeout(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP VIEW IF EXISTS " + qualifiedView(existing.viewName()));
                statement.execute("DELETE FROM app.dataset_views WHERE dataset_id = " + literal(datasetId));
            }
            LOGGER.info("dataset view dropped, datasetId={}, view={}", datasetId, existing.viewName());
        });
    }

    public List<Column> columns(Dataset dataset) {
        var used = new LinkedHashSet<String>();
        var columns = new ArrayList<Column>();
        if (dataset.schema == null) return columns;
        for (var field : dataset.schema) {
            if (field == null || field.name == null || field.name.isBlank()) continue;
            var columnName = uniqueName(normalize(field.name), used);
            columns.add(new Column(field.name, columnName, field.type != null ? field.type : SchemaFieldType.STRING));
        }
        return columns;
    }

    public ViewPlan plan(Dataset dataset) {
        var columns = columns(dataset);
        var viewName = viewName(dataset.id);
        var selectList = selectList(columns);
        var statements = new ArrayList<String>();
        statements.add("DROP VIEW IF EXISTS " + qualifiedView(viewName));
        statements.add("CREATE VIEW " + qualifiedView(viewName) + " WITH (security_invoker = true) AS SELECT "
                + selectList + " FROM dataset.dataset_records WHERE dataset_id = " + literal(dataset.id));
        return new ViewPlan(viewName, columns, schemaHash(selectList), statements);
    }

    /**
     * Creates an index on the exact expression the view uses for the field, so queries routed through the
     * view can be served by the index. Only worth doing for fields a dataset filters on frequently.
     */
    public void indexField(Dataset dataset, String fieldName) {
        var column = columns(dataset).stream()
                .filter(candidate -> candidate.sourceName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown field: " + fieldName));
        var name = truncate("ix_" + viewName(dataset.id) + "_" + column.columnName(), 60);
        ddl.executeAll(List.of("CREATE INDEX IF NOT EXISTS " + quoteIdentifier(name)
                + " ON dataset.dataset_records (dataset_id, (" + expression(column) + "))"));
    }

    private Registry findRegistry(Connection connection, String datasetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT view_name, schema_hash FROM app.dataset_views WHERE dataset_id = ?")) {
            statement.setString(1, datasetId);
            var resultSet = statement.executeQuery();
            if (!resultSet.next()) return null;
            return new Registry(resultSet.getString("view_name"), resultSet.getString("schema_hash"));
        }
    }

    private boolean viewExists(Connection connection, String viewName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_views WHERE schemaname = ? AND viewname = ?")) {
            statement.setString(1, "dataset");
            statement.setString(2, viewName);
            return statement.executeQuery().next();
        }
    }

    private void setLockTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '3s'");
        }
    }

    private String selectList(List<Column> columns) {
        var builder = new StringBuilder(SYSTEM_COLUMNS);
        for (var column : columns) {
            builder.append(", ").append(expression(column)).append(" AS ").append(quoteIdentifier(column.columnName()));
        }
        return builder.toString();
    }

    private void upsertRegistry(Connection connection, String datasetId, ViewPlan plan) throws SQLException {
        var columnMap = new LinkedHashMap<String, String>();
        for (var column : plan.columns()) {
            columnMap.put(column.sourceName(), column.columnName());
        }
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_REGISTRY)) {
            statement.setString(1, datasetId);
            statement.setString(2, plan.viewName());
            statement.setString(3, JsonUtil.toJson(columnMap));
            statement.setString(4, plan.schemaHash());
            statement.setObject(5, ZonedDateTime.now().toOffsetDateTime());
            statement.executeUpdate();
        }
    }

    private record Registry(String viewName, String schemaHash) {
    }

    public record Column(String sourceName, String columnName, SchemaFieldType type) {
    }

    public record ViewPlan(String viewName, List<Column> columns, String schemaHash, List<String> statements) {
    }
}
