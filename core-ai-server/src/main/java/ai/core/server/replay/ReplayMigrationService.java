package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class ReplayMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayMigrationService.class);
    private static final String LOCK_KEY = "replay_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<ReplayExperiment> experimentCollection;
    @Inject
    MongoCollection<ReplayRun> runCollection;
    @Inject
    ReplayExperimentStore experimentStore;
    @Inject
    ReplayRunStore runStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgReplayMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("replay migration skipped, another instance holds the lock");
                return;
            }
            long experiments = copy("replay_experiments_v1", experimentCollection, experimentStore::insert);
            long runs = copy("replay_runs_v1", runCollection, runStore::insert);
            transaction.commit();
            LOGGER.info("replay migration finished, experiments={}, runs={}", experiments, runs);
        }
    }

    private <T> long copy(String marker, MongoCollection<T> collection, Consumer<T> insert) {
        if (migrationDone(marker)) return 0;
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<T> page = collection.find(query);
            if (page.isEmpty()) break;
            for (T item : page) {
                try {
                    insert.accept(item);
                } catch (RuntimeException e) {
                    LOGGER.debug("replay row already migrated, marker={}", marker, e);
                }
                lastId = idOf(item);
                count++;
            }
        }
        insertMarker(marker);
        return count;
    }

    private String idOf(Object item) {
        if (item instanceof ReplayExperiment experiment) return experiment.id;
        return ((ReplayRun) item).id;
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgReplayMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }
}
