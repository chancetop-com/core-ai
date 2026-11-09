package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillStats;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PgSkillStore implements SkillStore {
    private static final String TABLE = "app.skills";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static SkillDefinition toSkill(PgDocumentRow row) {
        return JsonUtil.fromJson(SkillDefinition.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<SkillDefinition> store() {
        return new PgDocumentStore<>(database, TABLE, SkillDefinition.class, definition -> definition.id);
    }

    @Override
    public Optional<SkillDefinition> get(String id) {
        return store().get(id);
    }

    @Override
    public Optional<SkillDefinition> findByQualifiedName(String qualifiedName) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "qualifiedName", qualifiedName)
            .map(PgSkillStore::toSkill);
    }

    @Override
    public List<SkillDefinition> findByName(String name) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "name", name)
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public List<SkillDefinition> listByIds(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        var placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return database.select(SELECT + " WHERE id IN (" + placeholders + ")", PgDocumentRow.class, ids.toArray())
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public List<SkillDefinition> list(String namespace, SkillSourceType sourceType, Integer offset, Integer limit) {
        var params = new ArrayList<Object>();
        var sql = new StringBuilder(SELECT.length() + 160).append(SELECT).append(where(namespace, sourceType, params))
            .append(" ORDER BY updated_at DESC, id DESC");
        if (offset != null || limit != null) {
            sql.append(" LIMIT ? OFFSET ?");
            params.add(Math.clamp(limit != null ? limit : 20, 1, 100));
            params.add(Math.max(0, offset != null ? offset : 0));
        }
        return database.select(sql.toString(), PgDocumentRow.class, params.toArray())
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public long count(String namespace, SkillSourceType sourceType) {
        var params = new ArrayList<Object>();
        var sql = "SELECT count(*) AS total FROM " + TABLE + where(namespace, sourceType, params);
        return database.selectOne(sql, PgSkillCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<SkillDefinition> listBySourceType(SkillSourceType sourceType) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "sourceType", sourceType.name())
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public List<SkillDefinition> listAll() {
        return database.select(SELECT + " ORDER BY updated_at DESC, id DESC", PgDocumentRow.class)
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public List<SkillDefinition> listCatalog() {
        var sql = "SELECT (CASE WHEN jsonb_typeof(data -> ?) = ? THEN jsonb_set(data - ?, ?::text[], "
            + "COALESCE((SELECT jsonb_agg(elem - ?) FROM jsonb_array_elements(data -> ?) elem), jsonb_build_array())) "
            + "ELSE data - ? END)::text AS data FROM " + TABLE
            + " ORDER BY updated_at DESC, id DESC";
        return database.select(sql, PgDocumentRow.class,
                "resources", "array", "content", "{resources}", "content", "resources", "content")
            .stream().map(PgSkillStore::toSkill).toList();
    }

    @Override
    public long countByNamespace(String namespace) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ?",
            PgSkillCountRow.class, "namespace", namespace).map(row -> row.total).orElse(0L);
    }

    @Override
    public long countBySourceType(SkillSourceType sourceType) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ?",
            PgSkillCountRow.class, "sourceType", sourceType.name()).map(row -> row.total).orElse(0L);
    }

    @Override
    public void insert(SkillDefinition definition) {
        store().insert(definition);
    }

    @Override
    public void replace(SkillDefinition definition) {
        store().replace(definition);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }

    @Override
    public void recordShow(String qualifiedName) {
        update("data ->> ? = ?", List.of("qualifiedName", qualifiedName), definition -> {
            if (definition.stats == null) definition.stats = new SkillStats();
            definition.stats.showCount = definition.stats.showCount == null ? 1 : definition.stats.showCount + 1;
        });
    }

    @Override
    public void recordPull(String id) {
        update("id = ?", List.of(id), definition -> {
            if (definition.stats == null) definition.stats = new SkillStats();
            definition.stats.pullCount = definition.stats.pullCount == null ? 1 : definition.stats.pullCount + 1;
            definition.stats.lastPulledAt = ZonedDateTime.now();
        });
    }

    private void update(String condition, List<Object> params, Consumer<SkillDefinition> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE " + condition + " FOR UPDATE", PgDocumentRow.class, params.toArray())
                .orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var definition = toSkill(row);
            mutator.accept(definition);
            store().replace(definition);
            transaction.commit();
        }
    }

    private String where(String namespace, SkillSourceType sourceType, List<Object> params) {
        var conditions = new ArrayList<String>();
        if (namespace != null && !namespace.isBlank()) {
            conditions.add("data ->> ? = ?");
            params.add("namespace");
            params.add(namespace);
        }
        if (sourceType != null) {
            conditions.add("data ->> ? = ?");
            params.add("sourceType");
            params.add(sourceType.name());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
}
