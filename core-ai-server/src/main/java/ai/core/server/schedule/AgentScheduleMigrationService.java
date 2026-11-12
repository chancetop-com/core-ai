package ai.core.server.schedule;

import ai.core.server.domain.AgentSchedule;
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
public class AgentScheduleMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScheduleMigrationService.class);
    private static final String MARKER = "agent_schedules_v1";
    private static final String LOCK_KEY = "agent_schedule_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;
    @Inject
    AgentScheduleStore agentScheduleStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgAgentScheduleMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("agent schedule migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("agent schedule migration finished, schedules={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgAgentScheduleMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = agentScheduleCollection.find(query);
            if (page.isEmpty()) return count;
            for (var schedule : page) {
                agentScheduleStore.insertIfAbsent(schedule);
                lastId = schedule.id;
                count++;
            }
        }
    }
}
