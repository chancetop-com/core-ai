package ai.core.server.domain;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class PgSessionAttachmentRefRepository implements SessionAttachmentRefRepository {
    private static final String TABLE = "app.session_attachment_ref";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    public Database database;

    private PgDocumentStore<SessionAttachmentRef> store() {
        return new PgDocumentStore<>(database, TABLE, SessionAttachmentRef.class, reference -> reference.id);
    }

    @Override
    public void insert(SessionAttachmentRef reference) {
        store().insert(reference);
    }

    @Override
    public boolean insertIfAbsent(SessionAttachmentRef reference) {
        return store().insertIfAbsent(reference);
    }

    @Override
    public SessionAttachmentRef findOwned(String referenceId, String sessionId, String userId) {
        var rows = database.select(SELECT + " WHERE id = ? AND data ->> ? = ? AND data ->> ? = ?",
            PgDocumentRow.class, referenceId, "sessionId", sessionId, "userId", userId);
        return rows.stream().findFirst().map(row -> JsonUtil.fromJson(SessionAttachmentRef.class, row.data)).orElse(null);
    }

    @Override
    public List<SessionAttachmentRef> findOwnedByPrefix(String prefix, String sessionId, String userId) {
        var rows = database.select(SELECT + " WHERE id LIKE ? AND data ->> ? = ? AND data ->> ? = ?",
            PgDocumentRow.class, prefix + "%", "sessionId", sessionId, "userId", userId);
        return rows.stream().map(row -> JsonUtil.fromJson(SessionAttachmentRef.class, row.data)).toList();
    }

    @Override
    public List<SessionAttachmentRef> findOwnedVideos(String sessionId, String userId) {
        return findByKind(sessionId, userId, SessionAttachmentRef.KIND_VIDEO);
    }

    @Override
    public void attachFile(String referenceId, String fileId) {
        var reference = store().get(referenceId).orElse(null);
        if (reference == null) return;
        reference.fileId = fileId;
        store().replace(reference);
    }

    @Override
    public SessionAttachmentRef findBySource(String sessionId, String userId, String container, String blobName) {
        var rows = database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
            PgDocumentRow.class, "sessionId", sessionId, "userId", userId, "container", container, "blobName", blobName);
        return rows.stream().findFirst().map(row -> JsonUtil.fromJson(SessionAttachmentRef.class, row.data)).orElse(null);
    }

    @Override
    public List<SessionAttachmentRef> findSandboxAttachments(String sessionId, String userId) {
        return findByKind(sessionId, userId, SessionAttachmentRef.KIND_SANDBOX);
    }

    private List<SessionAttachmentRef> findByKind(String sessionId, String userId, String kind) {
        var rows = database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? "
                + "ORDER BY data ->> ? DESC", PgDocumentRow.class,
            "sessionId", sessionId, "userId", userId, "kind", kind, "createdAt");
        return rows.stream().map(row -> JsonUtil.fromJson(SessionAttachmentRef.class, row.data)).toList();
    }
}

