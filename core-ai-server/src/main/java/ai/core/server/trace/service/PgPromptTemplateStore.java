package ai.core.server.trace.service;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgPromptTemplateStore implements PromptTemplateStore {
    private static final String TABLE = "app.prompt_templates";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static PromptTemplate toTemplate(PgDocumentRow row) {
        return JsonUtil.fromJson(PromptTemplate.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<PromptTemplate> store() {
        return new PgDocumentStore<>(database, TABLE, PromptTemplate.class, template -> template.id);
    }

    @Override
    public List<PromptTemplate> list(int offset, int limit) {
        return database.select(SELECT + " ORDER BY updated_at DESC LIMIT ? OFFSET ?", PgDocumentRow.class, limit, offset)
                .stream().map(PgPromptTemplateStore::toTemplate).toList();
    }

    @Override
    public Optional<PromptTemplate> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(PromptTemplate template) {
        store().insert(template);
    }

    @Override
    public void replace(PromptTemplate template) {
        store().replace(template);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
