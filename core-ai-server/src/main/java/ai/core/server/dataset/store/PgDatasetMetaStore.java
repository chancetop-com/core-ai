package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL metadata store, hand-written SQL on purpose: the schema is a JSON document and core-ng's
 * repository mapping restricts JSON columns to fixed beans.
 *
 * @author stephen
 */
public class PgDatasetMetaStore implements DatasetMetaStore {
    private static final String SELECT = "SELECT id, name, description, user_id, type, schema_json, schema_version, created_at, updated_at "
            + "FROM app.datasets";

    private static String where(String query, List<Object> params) {
        if (query == null || query.isBlank()) return "";
        var pattern = "%" + escapeLike(query.trim()) + "%";
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);
        return " WHERE (name ILIKE ? OR description ILIKE ? OR user_id ILIKE ?)";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String type(DatasetType type) {
        return type != null ? type.name() : null;
    }

    private static String schemaJson(Dataset dataset) {
        return dataset.schema != null ? JsonUtil.toJson(dataset.schema) : "[]";
    }

    private static Dataset toDataset(PgDatasetRow row) {
        var dataset = new Dataset();
        dataset.id = row.id;
        dataset.name = row.name;
        dataset.description = row.description;
        dataset.userId = row.userId;
        dataset.type = row.type != null ? DatasetType.valueOf(row.type) : null;
        dataset.schema = row.schemaJson != null ? JsonUtil.fromJson(SCHEMA_TYPE, row.schemaJson) : List.of();
        dataset.createdAt = row.createdAt;
        dataset.updatedAt = row.updatedAt;
        return dataset;
    }

    private static final TypeReference<List<SchemaField>> SCHEMA_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Override
    public List<Dataset> list(String query, Integer offset, Integer limit) {
        var params = new ArrayList<>();
        var where = where(query, params);
        int size = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;
        int skip = offset != null ? Math.max(offset, 0) : 0;
        params.add(size);
        params.add(skip);
        return database.select(SELECT + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?", PgDatasetRow.class, params.toArray())
                .stream().map(PgDatasetMetaStore::toDataset).toList();
    }

    @Override
    public long count(String query) {
        var params = new ArrayList<>();
        var where = where(query, params);
        return database.selectOne("SELECT count(*) AS total FROM app.datasets" + where, PgCountRow.class, params.toArray())
                .map(row -> row.total)
                .orElse(0L);
    }

    @Override
    public Dataset get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgDatasetRow.class, id).map(PgDatasetMetaStore::toDataset).orElse(null);
    }

    @Override
    public void create(Dataset dataset) {
        database.execute("INSERT INTO app.datasets (id, name, description, user_id, type, schema_json, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)",
                dataset.id, dataset.name, dataset.description, dataset.userId, type(dataset.type), schemaJson(dataset),
                dataset.createdAt, dataset.updatedAt);
    }

    @Override
    public void update(Dataset dataset) {
        database.execute("UPDATE app.datasets SET name = ?, description = ?, type = ?, schema_json = ?, "
                        + "schema_version = schema_version + CASE WHEN schema_json IS DISTINCT FROM ? THEN 1 ELSE 0 END, "
                        + "updated_at = ? WHERE id = ?",
                dataset.name, dataset.description, type(dataset.type), schemaJson(dataset), schemaJson(dataset),
                dataset.updatedAt, dataset.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM app.datasets WHERE id = ?", id);
    }
}
