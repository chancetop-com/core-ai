package ai.core.server.file;

import ai.core.server.domain.FileRecord;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * File metadata storage. Content never lives here: bytes are in object storage, and the PostgreSQL
 * implementation rejects a record that still carries inline data.
 *
 * @author stephen
 */
public interface FileRecordStore {
    Optional<FileRecord> get(String id);

    List<FileRecord> find(FileRecordQuery query);

    long count(FileRecordQuery query);

    void insert(FileRecord record);

    /** @return false when the id already exists */
    boolean insertIfAbsent(FileRecord record);

    void updateThumbStoragePath(String id, String path);

    void updateFileName(String id, String fileName);

    /** legacy inline-content migration: object storage now owns the bytes */
    void markStored(String id, String storagePath);

    /** @return true when this caller won the right to use the token */
    boolean claimShareToken(String id, String userId, String token, ZonedDateTime sharedAt);

    List<String> sharedUserIds();

    void delete(String id);
}
