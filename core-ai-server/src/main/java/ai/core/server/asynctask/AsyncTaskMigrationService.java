package ai.core.server.asynctask;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class AsyncTaskMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncTaskMigrationService.class);
    private static final String MARKER = "async_tool_tasks_v1";
    private static final String LOCK_KEY = "async_task_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AsyncToolTaskRecord> taskCollection;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgAsyncTaskMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("async task migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("async task migration finished, tasks={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgAsyncTaskMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<AsyncToolTaskRecord> page = taskCollection.find(query);
            if (page.isEmpty()) return count;
            for (var task : page) {
                database.execute("INSERT INTO app.async_tool_tasks (id, data, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", task.id, task.data, task.updatedAt);
                lastId = task.id;
                count++;
            }
        }
    }
}

