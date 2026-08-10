package ai.core.server.file;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Migrates legacy Mongo base64 file content to object storage.
 * Runs periodically; records without a storage_path are processed in small batches
 * and both read paths stay available until the batch finishes, so no downtime is needed.
 *
 * @author stephen
 */
public class FileStorageMigrationJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageMigrationJob.class);
    private static final int BATCH_SIZE = 50;
    private static final String ARTIFACT_PREFIX = "artifacts/";

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    @Inject
    ObjectStorageServiceResolver storageResolver;

    @Override
    public void execute(JobContext context) {
        var storage = storageResolver.resolve();
        if (storage == null) {
            LOGGER.warn("object storage is not configured, migration skipped");
            return;
        }
        var container = storageResolver.artifactContainer();

        var query = new Query();
        query.filter = Filters.and(
                Filters.type("data", "string"),
                Filters.exists("storage_path", false));
        query.sort = Sorts.ascending("created_at");
        query.limit = BATCH_SIZE;

        var records = fileRecordCollection.find(query);
        if (records.isEmpty()) return;

        int migrated = 0;
        int failed = 0;
        for (var record : records) {
            try {
                migrate(storage, container, record);
                migrated++;
            } catch (RuntimeException e) {
                failed++;
                LOGGER.warn("file migration failed, id={}", record.id, e);
            }
        }
        LOGGER.info("file-storage-migration migrated={}, failed={}, batchSize={}", migrated, failed, records.size());
    }

    private void migrate(ObjectStorageService storage, String container, FileRecord record) {
        if (record.data == null) return;
        var blobName = ARTIFACT_PREFIX + record.id + FileService.extension(record.contentType);
        var tempFile = createTempFile(record.id);
        try {
            Files.write(tempFile, Base64.getDecoder().decode(record.data));
            storage.uploadObject(container, blobName, tempFile, record.contentType);
            fileRecordCollection.update(Filters.eq("_id", record.id), Updates.combine(
                    Updates.set("storage_path", container + "/" + blobName),
                    Updates.unset("data")));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write temp file for migration, id=" + record.id, e);
        } finally {
            deleteTempFileQuietly(tempFile);
        }
    }

    private Path createTempFile(String id) {
        try {
            return Files.createTempFile("file-migration-" + id, ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create temp file for migration", e);
        }
    }

    private void deleteTempFileQuietly(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp file, path={}", tempFile, e);
        }
    }
}
