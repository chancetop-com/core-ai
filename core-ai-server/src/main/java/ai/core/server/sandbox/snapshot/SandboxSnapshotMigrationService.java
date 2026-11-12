package ai.core.server.sandbox.snapshot;

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
public class SandboxSnapshotMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSnapshotMigrationService.class);
    private static final String SNAPSHOT_MARKER = "sandbox_snapshots_v1";
    private static final String EPOCH_MARKER = "sandbox_epochs_v1";
    private static final String LOCK_KEY = "sandbox_snapshot_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<SandboxSnapshotDoc> snapshotCollection;
    @Inject
    MongoCollection<SandboxEpochDoc> epochCollection;
    @Inject
    SandboxSnapshotStore snapshotStore;
    @Inject
    SandboxEpochStore epochStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgSandboxSnapshotMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("sandbox snapshot migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone(SNAPSHOT_MARKER) && migrationDone(EPOCH_MARKER)) {
                transaction.commit();
                return;
            }
            long snapshots = migrationDone(SNAPSHOT_MARKER) ? 0 : copySnapshots();
            long epochs = migrationDone(EPOCH_MARKER) ? 0 : copyEpochs();
            insertMarker(SNAPSHOT_MARKER);
            insertMarker(EPOCH_MARKER);
            transaction.commit();
            LOGGER.info("sandbox snapshot migration finished, snapshots={}, epochs={}", snapshots, epochs);
        }
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgSandboxSnapshotMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }

    private long copySnapshots() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<SandboxSnapshotDoc> page = snapshotCollection.find(query);
            if (page.isEmpty()) return count;
            for (var doc : page) {
                try {
                    snapshotStore.insert(doc);
                } catch (RuntimeException e) {
                    LOGGER.debug("snapshot already migrated, id={}", doc.id);
                }
                lastId = doc.id;
                count++;
            }
        }
    }

    private long copyEpochs() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<SandboxEpochDoc> page = epochCollection.find(query);
            if (page.isEmpty()) return count;
            for (var doc : page) {
                epochStore.insertIfAbsent(doc);
                lastId = doc.id;
                count++;
            }
        }
    }
}
