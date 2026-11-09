package ai.core.server.project;

import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PgProjectReportDraftStore implements ProjectReportDraftStore {
    private static final String TABLE = "app.project_report_drafts";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    Database database;

    private PgDocumentStore<ProjectReportDraft> store() {
        return new PgDocumentStore<>(database, TABLE, ProjectReportDraft.class, draft -> draft.id);
    }

    @Override
    public Optional<ProjectReportDraft> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(ProjectReportDraft draft) {
        store().insert(draft);
    }

    @Override
    public void update(String id, Consumer<ProjectReportDraft> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgDocumentRow.class, id).orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var draft = JsonUtil.fromJson(ProjectReportDraft.class, row.data);
            mutator.accept(draft);
            store().replace(draft);
            transaction.commit();
        }
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ?", "subjectId", subjectId);
    }
}
