package ai.core.server.costalert;

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
public class CostAlertMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CostAlertMigrationService.class);
    private static final String RULE_MARKER = "cost_alert_rules_v1";
    private static final String EVENT_MARKER = "cost_alert_events_v1";
    private static final String LOCK_KEY = "cost_alert_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<CostAlertRule> ruleCollection;
    @Inject
    MongoCollection<CostAlertEvent> eventCollection;
    @Inject
    CostAlertRuleStore ruleStore;
    @Inject
    CostAlertEventStore eventStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgCostAlertMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("cost alert migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone(RULE_MARKER) && migrationDone(EVENT_MARKER)) {
                transaction.commit();
                return;
            }
            long rules = migrationDone(RULE_MARKER) ? 0 : copyRules();
            long events = migrationDone(EVENT_MARKER) ? 0 : copyEvents();
            insertMarker(RULE_MARKER);
            insertMarker(EVENT_MARKER);
            transaction.commit();
            LOGGER.info("cost alert migration finished, rules={}, events={}", rules, events);
        }
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgCostAlertMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }

    private long copyRules() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<CostAlertRule> page = ruleCollection.find(query);
            if (page.isEmpty()) return count;
            for (var rule : page) {
                ruleStore.insertIfAbsent(rule);
                lastId = rule.id;
                count++;
            }
        }
    }

    private long copyEvents() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<CostAlertEvent> page = eventCollection.find(query);
            if (page.isEmpty()) return count;
            for (var event : page) {
                eventStore.insertIfAbsent(event);
                lastId = event.id;
                count++;
            }
        }
    }
}
