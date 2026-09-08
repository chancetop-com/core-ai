package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Stop expiring object-storage-backed artifacts. {@link SchemaMigrationVFileRecordsTTL} added a 30-day TTL on
 * {@code file_records.created_at} when artifact bytes were inlined into Mongo, to bound collection growth.
 * Since {@code FileService} moved bytes to object storage the TTL only deleted the metadata row: the blob stayed
 * in storage (Mongo TTL bypasses {@code FileService.delete}), while share tokens, session artifacts and project
 * subject reports pointing at the row went dangling.
 * <p>
 * This replaces the blanket TTL with a partial one that only covers legacy inline records (those with a
 * {@code data} field). Records with {@code storage_path} are kept indefinitely.
 *
 * @author stephen
 */
public class SchemaMigrationVFileRecordsDropTTL implements SchemaMigration {
    static final long INLINE_DATA_TTL_SECONDS = 2592000L;   // 30 days, unchanged for inline records
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationVFileRecordsDropTTL.class);

    @Override
    public String version() {
        return "20260908002";
    }

    @Override
    public String description() {
        return "drop 30d TTL on file_records for object-storage artifacts; keep TTL only for legacy inline data";
    }

    @Override
    public void migrate(Mongo mongo) {
        try {
            mongo.dropIndex("file_records", Indexes.ascending("created_at"));
        } catch (RuntimeException e) {
            LOGGER.debug("file_records created_at TTL index not present, skipping drop: {}", e.getMessage());
        }
        mongo.createIndex("file_records", Indexes.ascending("created_at"),
            new IndexOptions().expireAfter(INLINE_DATA_TTL_SECONDS, TimeUnit.SECONDS)
                .partialFilterExpression(new Document("data", new Document("$type", "string"))));
    }
}
