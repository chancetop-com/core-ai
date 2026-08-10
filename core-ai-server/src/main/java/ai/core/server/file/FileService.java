package ai.core.server.file;

import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.FileRecord;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * @author stephen
 */
public class FileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ARTIFACT_PREFIX = "artifacts/";

    static String extension(String contentType) {
        if (contentType == null) return "";
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "video/x-msvideo" -> ".avi";
            case "application/pdf" -> ".pdf";
            case "application/zip" -> ".zip";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            case "text/markdown" -> ".md";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            default -> "";
        };
    }

    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

    @Inject
    ObjectStorageServiceResolver storageResolver;

    public FileRecord upload(String userId, String fileName, String contentType, Path tempFile) {
        var id = UUID.randomUUID().toString();
        var record = new FileRecord();
        record.id = id;
        record.userId = userId;
        record.fileName = fileName;
        record.contentType = contentType;
        record.createdAt = ZonedDateTime.now();

        var storage = storageResolver.resolve();
        if (storage != null) {
            var container = storageResolver.artifactContainer();
            var blobName = ARTIFACT_PREFIX + id + extension(contentType);
            storage.uploadObject(container, blobName, tempFile, contentType);
            record.storagePath = container + "/" + blobName;
            record.size = tempFile.toFile().length();
        } else {
            var raw = readAllBytes(tempFile);
            record.data = Base64.getEncoder().encodeToString(raw);
            record.size = (long) raw.length;
        }
        deleteTempFile(tempFile);

        fileRecordCollection.insert(record);
        LOGGER.info("file uploaded, id={}, fileName={}, size={}", id, fileName, record.size);
        return record;
    }

    private byte[] readAllBytes(Path tempFile) {
        try {
            return Files.readAllBytes(tempFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read file", e);
        }
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

    public FileRecord share(String id, String userId) {
        var record = get(id);
        ensureOwner(record, userId);
        if (record.shareToken != null && !record.shareToken.isBlank()) {
            return record;
        }

        record.shareToken = newShareToken();
        record.sharedAt = ZonedDateTime.now();
        var updated = fileRecordCollection.update(Filters.and(
                Filters.eq("_id", id),
                Filters.eq("user_id", userId),
                Filters.or(
                    Filters.exists("share_token", false),
                    Filters.eq("share_token", null),
                    Filters.eq("share_token", ""))),
            Updates.combine(
                Updates.set("share_token", record.shareToken),
                Updates.set("shared_at", record.sharedAt)));
        if (updated == 0) {
            var latest = get(id);
            ensureOwner(latest, userId);
            if (latest.shareToken != null && !latest.shareToken.isBlank()) {
                return latest;
            }
            throw new NotFoundException("file not found, id=" + id);
        }
        LOGGER.info("file share created, id={}, fileName={}", id, record.fileName);
        return record;
    }

    private void ensureOwner(FileRecord record, String userId) {
        if (!Objects.equals(record.userId, userId)) {
            throw new ForbiddenException("file does not belong to current user");
        }
    }

    public FileRecord getShared(String token) {
        if (token == null || token.isBlank()) {
            throw new NotFoundException("shared file not found");
        }
        return fileRecordCollection.findOne(Filters.and(
                Filters.eq("share_token", token),
                Filters.type("share_token", "string")))
            .orElseThrow(() -> new NotFoundException("shared file not found"));
    }

    private String newShareToken() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Get the decoded file bytes for a record. Prefers object storage when the record was migrated.
     */
    public byte[] getBytes(FileRecord record) {
        if (record.storagePath != null) {
            var storage = storageResolver.resolve();
            if (storage == null) {
                throw new NotFoundException("object storage is not configured, id=" + record.id);
            }
            return storage.downloadObject(containerOf(record.storagePath), blobOf(record.storagePath));
        }
        if (record.data == null) {
            throw new NotFoundException("file content not available, id=" + record.id
                + " (file may have expired or been stored on a different instance)");
        }
        return Base64.getDecoder().decode(record.data);
    }

    /**
     * Pre-signed download URL for direct browser access, or null when content is still in Mongo.
     */
    public String downloadUrl(FileRecord record) {
        if (record.storagePath == null) return null;
        var storage = storageResolver.resolve();
        if (storage == null) return null;
        return storage.generateDownloadCredential(containerOf(record.storagePath), blobOf(record.storagePath)).downloadUrl();
    }

    public void delete(String id) {
        var record = fileRecordCollection.get(id).orElse(null);
        if (record != null && record.storagePath != null) {
            var storage = storageResolver.resolve();
            if (storage != null) {
                try {
                    storage.deleteObject(containerOf(record.storagePath), blobOf(record.storagePath));
                } catch (RuntimeException e) {
                    LOGGER.warn("failed to delete object, id={}, storagePath={}", id, record.storagePath, e);
                }
            }
        }
        fileRecordCollection.delete(id);
    }

    private String containerOf(String storagePath) {
        return storagePath.substring(0, storagePath.indexOf('/'));
    }

    private String blobOf(String storagePath) {
        return storagePath.substring(storagePath.indexOf('/') + 1);
    }
}
