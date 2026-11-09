package ai.core.server.systemprompt;

import ai.core.server.domain.SystemPrompt;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class PgSystemPromptStore implements SystemPromptStore {
    private static final String TABLE = "app.system_prompts";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static SystemPrompt toPrompt(PgDocumentRow row) {
        return JsonUtil.fromJson(SystemPrompt.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<SystemPrompt> store() {
        return new PgDocumentStore<>(database, TABLE, SystemPrompt.class, prompt -> prompt.id);
    }

    @Override
    public void insert(SystemPrompt prompt) {
        store().insert(prompt);
    }

    @Override
    public SystemPrompt latestByPromptId(String promptId) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? ORDER BY (data ->> ?)::int DESC LIMIT 1",
                        PgDocumentRow.class, "promptId", promptId, "version")
                .map(PgSystemPromptStore::toPrompt).orElse(null);
    }

    @Override
    public SystemPrompt latestByName(String name) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? ORDER BY (data ->> ?)::int DESC LIMIT 1",
                        PgDocumentRow.class, "name", name, "version")
                .map(PgSystemPromptStore::toPrompt).orElse(null);
    }

    @Override
    public List<SystemPrompt> listAll() {
        return database.select(SELECT + " ORDER BY (data ->> ?)::timestamptz DESC", PgDocumentRow.class, "createdAt")
                .stream().map(PgSystemPromptStore::toPrompt).toList();
    }

    @Override
    public List<SystemPrompt> versions(String promptId) {
        return database.select(SELECT + " WHERE data ->> ? = ? ORDER BY (data ->> ?)::int DESC",
                        PgDocumentRow.class, "promptId", promptId, "version")
                .stream().map(PgSystemPromptStore::toPrompt).toList();
    }

    @Override
    public SystemPrompt version(String promptId, int version) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? AND (data ->> ?)::int = ?",
                        PgDocumentRow.class, "promptId", promptId, "version", version)
                .map(PgSystemPromptStore::toPrompt).orElse(null);
    }

    @Override
    public void deleteByPromptId(String promptId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ?", "promptId", promptId);
    }
}
