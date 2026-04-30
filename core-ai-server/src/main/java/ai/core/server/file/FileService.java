package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * @author stephen
 */
public class FileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileService.class);

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    public FileRecord upload(String userId, String fileName, String contentType, Path tempFile) {
        var id = UUID.randomUUID().toString();

        byte[] raw;
        try {
            raw = Files.readAllBytes(tempFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read file", e);
        }
        deleteTempFile(tempFile);

        var record = new FileRecord();
        record.id = id;
        record.userId = userId;
        record.fileName = fileName;
        record.contentType = contentType;
        record.size = (long) raw.length;
        record.data = Base64.getEncoder().encodeToString(raw);
        record.createdAt = ZonedDateTime.now();

        fileRecordCollection.insert(record);
        LOGGER.info("file uploaded, id={}, fileName={}, size={}", id, fileName, record.size);
        return record;
    }

    private void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temp file, path={}", tempFile, e);
        }
    }

    public FileRecord get(String id) {
        return fileRecordCollection.get(id)
            .orElseThrow(() -> new NotFoundException("file not found, id=" + id));
    }

    /**
     * Get the decoded file bytes for a record.
     */
    public byte[] getBytes(FileRecord record) {
        if (record.data == null) {
            throw new NotFoundException("file content not available, id=" + record.id
                + " (file may have expired or been stored on a different instance)");
        }
        return Base64.getDecoder().decode(record.data);
    }

    public void delete(String id) {
        fileRecordCollection.delete(id);
    }
}
