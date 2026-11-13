package ai.core.server.share;

import ai.core.server.domain.SessionShare;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author Xander
 */
public class PgSessionShareStore implements SessionShareStore {
    private static final String TABLE = "app.session_shares";
    private static final String SELECT = "SELECT data::text AS data, revoked_at FROM " + TABLE;

    private static SessionShare fromRow(PgSessionShareRow row) {
        var share = JsonUtil.fromJson(SessionShare.class, row.data);
        share.revokedAt = row.revokedAt;
        return share;
    }

    @Inject
    Database database;

    @Override
    public Optional<SessionShare> getBySessionId(String sessionId) {
        return database.selectOne(SELECT + " WHERE session_id = ?", PgSessionShareRow.class, sessionId)
                .map(PgSessionShareStore::fromRow);
    }

    @Override
    public Optional<SessionShare> getByToken(String token) {
        return database.selectOne(SELECT + " WHERE token = ?", PgSessionShareRow.class, token)
                .map(PgSessionShareStore::fromRow);
    }

    @Override
    public boolean replace(SessionShare value, String expectedId) {
        var json = JsonSanitizer.json(JsonUtil.toJson(value));
        if (expectedId == null) {
            return database.execute("INSERT INTO " + TABLE
                            + " (id, session_id, token, created_at, expires_at, revoked_at, data) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)"
                            + " ON CONFLICT DO NOTHING",
                    value.id, value.sessionId, value.token, value.createdAt, value.expiresAt, value.revokedAt, json) > 0;
        }
        return database.execute("UPDATE " + TABLE
                        + " SET id = ?, token = ?, created_at = ?, expires_at = ?, revoked_at = ?, data = ?::jsonb, updated_at = now()"
                        + " WHERE session_id = ? AND id = ?",
                value.id, value.token, value.createdAt, value.expiresAt, value.revokedAt, json, value.sessionId, expectedId) > 0;
    }

    @Override
    public void revoke(String sessionId, String shareId, ZonedDateTime now) {
        database.execute("UPDATE " + TABLE + " SET revoked_at = ?, updated_at = now() WHERE session_id = ? AND id = ? AND revoked_at IS NULL",
                now, sessionId, shareId);
    }
}
