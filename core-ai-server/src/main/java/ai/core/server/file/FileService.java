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
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * @author stephen
 */
public class FileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileService.class);
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path storageRoot;

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    public FileService(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public FileRecord upload(String userId, String fileName, String contentType, Path tempFile) {
        var id = UUID.randomUUID().toString();
        var now = ZonedDateTime.now();
        var relativePath = now.format(DIR_FORMAT) + "/" + id + "_" + sanitizeFileName(fileName);

        var targetPath = storageRoot.resolve(relativePath);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.move(tempFile, targetPath);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store file", e);
        }

        var record = new FileRecord();
        record.id = id;
        record.userId = userId;
        record.fileName = fileName;
        record.contentType = contentType;
        record.size = fileSize(targetPath);
        record.storagePath = relativePath;
        record.createdAt = now;

        fileRecordCollection.insert(record);
        LOGGER.info("file uploaded, id={}, fileName={}, size={}", id, fileName, record.size);
        return record;
    }

    public FileRecord get(String id) {
        return fileRecordCollection.get(id)
            .orElseThrow(() -> new NotFoundException("file not found, id=" + id));
    }

    public Path resolve(FileRecord record) {
        return storageRoot.resolve(record.storagePath);
    }

    public void delete(String id) {
        var record = get(id);
        var filePath = storageRoot.resolve(record.storagePath);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOGGER.warn("failed to delete file from disk, path={}", filePath, e);
        }
        fileRecordCollection.delete(id);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
