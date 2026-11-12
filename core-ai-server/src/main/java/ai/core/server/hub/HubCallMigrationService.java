package ai.core.server.hub;

import ai.core.server.domain.HubCall;
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
public class HubCallMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HubCallMigrationService.class);
    private static final String MARKER = "hub_calls_v1";
    private static final String LOCK_KEY = "hub_call_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<HubCall> callCollection;
    @Inject
    HubCallStore callStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgHubCallMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("hub call migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("hub call migration finished, calls={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgHubCallMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<HubCall> page = callCollection.find(query);
            if (page.isEmpty()) return count;
            for (var call : page) {
                try {
                    callStore.insert(call);
                } catch (RuntimeException e) {
                    LOGGER.debug("hub call already migrated, id={}", call.id);
                }
                lastId = call.id;
                count++;
            }
        }
    }
}

