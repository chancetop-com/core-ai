package ai.core.server.session;

import ai.core.server.domain.SessionNotice;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class PgSessionNoticeStore extends SessionNoticeStore {
    private static final String TABLE = "app.session_notices";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    public Database database;

    private PgDocumentStore<SessionNotice> store() {
        return new PgDocumentStore<>(database, TABLE, SessionNotice.class, notice -> notice.id);
    }

    @Override
    protected void insert(SessionNotice notice) {
        store().insert(notice);
    }

    @Override
    public boolean insertIfAbsent(SessionNotice notice) {
        return store().insertIfAbsent(notice);
    }

    @Override
    protected void updateAnswered(String userId, String channelId, String recipient, ZonedDateTime at) {
        database.execute("UPDATE " + TABLE + " SET data = data || jsonb_build_object(?, ?::boolean, ?, ?::text), "
                + "updated_at = now() WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
            "answered", "true", "answeredAt", at.toString(),
            "userId", userId, "channelId", channelId, "recipient", recipient);
    }

    @Override
    protected List<SessionNotice> findUnanswered(String userId, String channelId, String recipient) {
        var rows = database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? "
            + "AND (data ->> ?)::boolean = false ORDER BY data ->> ? DESC LIMIT ?", PgDocumentRow.class,
            "userId", userId, "channelId", channelId, "recipient", recipient, "answered", "createdAt", MAX_UNANSWERED);
        return rows.stream().map(row -> JsonUtil.fromJson(SessionNotice.class, row.data)).toList();
    }
}


