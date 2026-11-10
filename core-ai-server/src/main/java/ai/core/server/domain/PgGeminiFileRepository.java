package ai.core.server.domain;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class PgGeminiFileRepository implements GeminiFileRepository {
    private static final String TABLE = "app.gemini_files";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    public Database database;

    private PgDocumentStore<GeminiFile> store() {
        return new PgDocumentStore<>(database, TABLE, GeminiFile.class, file -> file.id);
    }

    @Override
    public GeminiFile findBySource(String userId, String providerId, String upstreamModel,
                                   String container, String blobName, String sourceETag) {
        var rows = database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? "
                + "AND data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?", PgDocumentRow.class,
            "userId", userId, "providerId", providerId, "upstreamModel", upstreamModel,
            "container", container, "blobName", blobName, "sourceETag", sourceETag);
        return rows.stream().findFirst().map(row -> JsonUtil.fromJson(GeminiFile.class, row.data)).orElse(null);
    }

    @Override
    public void insert(GeminiFile file) {
        store().insert(file);
    }

    @Override
    public boolean insertIfAbsent(GeminiFile file) {
        return store().insertIfAbsent(file);
    }

    @Override
    public void replace(GeminiFile file) {
        store().replace(file);
    }
}

