package ai.core.server.sandbox.snapshot;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgSandboxEpochStore implements SandboxEpochStore {
    private static final String TABLE = "app.sandbox_epochs";

    private static SandboxEpochDoc toDoc(PgSandboxEpochRow row) {
        var doc = new SandboxEpochDoc();
        doc.id = row.id;
        doc.epoch = row.epoch;
        doc.updatedAt = row.updatedAt;
        return doc;
    }

    @Inject
    public Database database;

    @Override
    public Optional<SandboxEpochDoc> get(String sessionId) {
        return database.selectOne("SELECT id, epoch, updated_at FROM " + TABLE + " WHERE id = ?",
                PgSandboxEpochRow.class, sessionId)
            .map(PgSandboxEpochStore::toDoc);
    }

    @Override
    public boolean insertIfAbsent(SandboxEpochDoc doc) {
        return database.execute("INSERT INTO " + TABLE + " (id, epoch, updated_at) VALUES (?, ?, ?) "
            + "ON CONFLICT DO NOTHING", doc.id, doc.epoch, doc.updatedAt) > 0;
    }

    @Override
    public boolean compareAndIncrement(String sessionId, long current, long next) {
        return database.execute("UPDATE " + TABLE + " SET epoch = ?, updated_at = ? WHERE id = ? AND epoch = ?",
            next, ZonedDateTime.now(), sessionId, current) > 0;
    }
}
