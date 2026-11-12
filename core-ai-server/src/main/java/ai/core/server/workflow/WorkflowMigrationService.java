package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
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
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class WorkflowMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowMigrationService.class);
    private static final String LOCK_KEY = "workflow_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;
    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;
    @Inject
    MongoCollection<WorkflowRun> runCollection;
    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;
    @Inject
    WorkflowDefinitionStore definitionStore;
    @Inject
    WorkflowVersionStore versionStore;
    @Inject
    WorkflowRunStore runStore;
    @Inject
    WorkflowNodeRunStore nodeRunStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgWorkflowMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("workflow migration skipped, another instance holds the lock");
                return;
            }
            long definitions = copy("workflow_definitions_v1", definitionCollection, definitionStore::insert);
            long versions = copy("workflow_published_versions_v1", versionCollection, versionStore::insert);
            long runs = copy("workflow_runs_v1", runCollection, runStore::insert);
            long nodeRuns = copy("workflow_node_runs_v1", nodeRunCollection, nodeRunStore::insertIfAbsent);
            transaction.commit();
            LOGGER.info("workflow migration finished, definitions={}, versions={}, runs={}, nodeRuns={}",
                definitions, versions, runs, nodeRuns);
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
                    if (!MigrationErrors.isDuplicateKey(e)) throw e;
                    LOGGER.debug("workflow row already migrated, marker={}", marker, e);
                }
                lastId = idOf(item);
                count++;
            }
        }
        insertMarker(marker);
        return count;
    }

    private String idOf(Object item) {
        if (item instanceof WorkflowDefinition definition) return definition.id;
        if (item instanceof WorkflowPublishedVersion version) return version.id;
        if (item instanceof WorkflowRun run) return run.id;
        return ((WorkflowNodeRun) item).id;
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgWorkflowMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }
}

