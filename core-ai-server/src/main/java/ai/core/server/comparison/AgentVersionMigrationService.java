package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentVersion;
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
public class AgentVersionMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentVersionMigrationService.class);
    private static final String MARKER = "agent_versions_v1";
    private static final String LOCK_KEY = "agent_version_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AgentVersion> versionCollection;
    @Inject
    AgentVersionStore agentVersionStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgAgentVersionMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("agent version migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("agent version migration finished, versions={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgAgentVersionMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = versionCollection.find(query);
            if (page.isEmpty()) return count;
            for (var version : page) {
                agentVersionStore.insertIfAbsent(version);
                lastId = version.id;
                count++;
            }
        }
    }
}
