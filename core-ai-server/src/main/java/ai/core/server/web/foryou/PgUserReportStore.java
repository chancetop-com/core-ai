package ai.core.server.web.foryou;

import ai.core.server.domain.UserReport;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgUserReportStore implements UserReportStore {
    private static final String TABLE = "app.user_reports";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    public Database database;

    private PgDocumentStore<UserReport> store() {
        return new PgDocumentStore<>(database, TABLE, UserReport.class, report -> report.id);
    }

    @Override
    public Optional<UserReport> get(String id) {
        return store().get(id);
    }

    @Override
    public List<UserReport> list(String userId) {
        return listLimited(userId, Integer.MAX_VALUE);
    }

    @Override
    public List<UserReport> recent(String userId, int limit) {
        return listLimited(userId, limit);
    }

    private List<UserReport> listLimited(String userId, int limit) {
        var rows = database.select(SELECT + " WHERE data ->> ? = ? ORDER BY data ->> ? DESC LIMIT ?",
            PgDocumentRow.class, "userId", userId, "updatedAt", limit);
        return rows.stream().map(row -> JsonUtil.fromJson(UserReport.class, row.data)).toList();
    }

    @Override
    public long count(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ?",
            PgForYouCountRow.class, "userId", userId).map(row -> row.total).orElse(0L);
    }

    @Override
    public void insert(UserReport report) {
        store().insert(report);
    }

    @Override
    public boolean insertIfAbsent(UserReport report) {
        return store().insertIfAbsent(report);
    }

    @Override
    public void replace(UserReport report) {
        store().replace(report);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}


