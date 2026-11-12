package ai.core.server.run;

import ai.core.server.domain.AgentRun;
import ai.core.server.util.MigrationErrors;
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
public class AgentRunMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunMigrationService.class);
    private static final String MARKER = "agent_runs_v1";
    private static final String LOCK_KEY = "agent_run_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    AgentRunStore agentRunStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgAgentRunMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("agent run migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("agent run migration finished, runs={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgAgentRunMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<AgentRun> page = agentRunCollection.find(query);
            if (page.isEmpty()) return count;
            for (var run : page) {
                try {
                    agentRunStore.insert(run);
                } catch (RuntimeException e) {
                    if (!MigrationErrors.isDuplicateKey(e)) throw e;
                    LOGGER.debug("agent run already migrated, id={}", run.id);
                }
                lastId = run.id;
                count++;
            }
        }
    }
}

