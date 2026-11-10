package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-way copy of file metadata from Mongo to PostgreSQL. File bytes stay in object storage; a record
 * that still has inline bytes aborts the copy instead of silently losing its content.
 *
 * @author stephen
 */
public class FileRecordMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileRecordMigrationService.class);
    private static final String MARKER = "file_records_v1";
    private static final int PAGE_SIZE = 200;
    private static final String LOCK_KEY = "file_record_migration";

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;
    @Inject
    FileRecordStore fileRecordStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgFileMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("file record migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long count = copy();
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("file record migration finished, files={}", count);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgFileMigrationMarkerRow.class, MARKER).isPresent();
    }

    private long copy() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = fileRecordCollection.find(query);
            if (page.isEmpty()) return count;
            for (var record : page) {
                if (record.data != null && record.storagePath == null) {
                    throw new IllegalStateException("file still has inline content; migrate it to object storage before "
                        + "file_records cutover, id=" + record.id);
                }
                fileRecordStore.insertIfAbsent(record);
                lastId = record.id;
                count++;
            }
        }
    }
}
