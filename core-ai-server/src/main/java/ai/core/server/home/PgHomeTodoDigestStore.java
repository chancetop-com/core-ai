package ai.core.server.home;

import ai.core.server.domain.HomeTodoDigestRun;
import ai.core.server.pg.PgDocumentStore;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;

/**
 * @author core-ai
 */
public class PgHomeTodoDigestStore implements HomeTodoDigestStore {
    private static final String TABLE = "app.home_todo_digest_runs";

    @Inject
    public Database database;

    private PgDocumentStore<HomeTodoDigestRun> store() {
        return new PgDocumentStore<>(database, TABLE, HomeTodoDigestRun.class, run -> run.id);
    }

    @Override
    public Optional<HomeTodoDigestRun> get(String id) {
        return store().get(id);
    }

    @Override
    public boolean insertIfAbsent(HomeTodoDigestRun run) {
        return store().insertIfAbsent(run);
    }

    @Override
    public void replace(HomeTodoDigestRun run) {
        store().replace(run);
    }
}
