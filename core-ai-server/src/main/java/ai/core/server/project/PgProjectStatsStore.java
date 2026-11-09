package ai.core.server.project;

import ai.core.server.domain.ProjectStatsEntity;
import ai.core.server.pg.PgDocumentRow;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;

/**
 * @author stephen
 */
public class PgProjectStatsStore implements ProjectStatsStore {
    private static final String TABLE = "app.project_stats";

    @Inject
    Database database;

    @Override
    public Optional<ProjectStatsEntity> get(String id) {
        return database.selectOne("SELECT data::text AS data FROM " + TABLE + " WHERE id = ?", PgDocumentRow.class, id)
            .map(row -> JsonUtil.fromJson(ProjectStatsEntity.class, row.data));
    }

    @Override
    public void replace(ProjectStatsEntity entity) {
        database.execute("INSERT INTO " + TABLE + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
                + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()",
            entity.id, JsonUtil.toJson(entity));
    }
}
