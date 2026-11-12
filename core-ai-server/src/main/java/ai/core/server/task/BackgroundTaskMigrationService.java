package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class BackgroundTaskMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskMigrationService.class);
    private static final String MARKER = "background_tasks_v1";
    private static final String LOCK_KEY = "background_task_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<BackgroundTask> taskCollection;
    @Inject
    BackgroundTaskStore taskStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgBackgroundTaskMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("background task migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("background task migration finished, tasks={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgBackgroundTaskMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = taskCollection.find(query);
            if (page.isEmpty()) return count;
            for (var task : page) {
                taskStore.insertIfAbsent(task);
                lastId = task.id;
                count++;
            }
        }
    }
}
