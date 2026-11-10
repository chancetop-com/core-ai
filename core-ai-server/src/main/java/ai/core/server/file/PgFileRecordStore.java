package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL file metadata store. Bytes are never stored here.
 *
 * @author stephen
 */
public class PgFileRecordStore implements FileRecordStore {
    private static final String TABLE = "app.file_records";
    private static final String SELECT = "SELECT id, user_id, file_name, content_type, size, content_hash, storage_path, "
        + "thumb_storage_path, share_token, shared_at, created_at FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, file_name, content_type, size, content_hash, "
        + "storage_path, thumb_storage_path, share_token, shared_at, created_at, updated_at) ";

    private static FileRecord toRecord(PgFileRecordRow row) {
        var record = new FileRecord();
        record.id = row.id;
        record.userId = row.userId;
        record.fileName = row.fileName;
        record.contentType = row.contentType;
        record.size = row.size;
        record.contentHash = row.contentHash;
        record.storagePath = row.storagePath;
        record.thumbStoragePath = row.thumbStoragePath;
        record.shareToken = row.shareToken;
        record.sharedAt = row.sharedAt;
        record.createdAt = row.createdAt;
        return record;
    }

    @Inject
    Database database;

    @Override
    public Optional<FileRecord> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgFileRecordRow.class, id).map(PgFileRecordStore::toRecord);
    }

    @Override
    public List<FileRecord> find(FileRecordQuery request) {
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        var sort = switch (request.sortField) {
            case "created_at", "createdAt" -> "created_at";
            case "shared_at", "sharedAt" -> "shared_at";
            default -> throw new IllegalArgumentException("unsupported file sort field: " + request.sortField);
        };
        var direction = request.ascending ? " ASC NULLS FIRST" : " DESC NULLS LAST";
        var orderBy = " ORDER BY " + sort + direction + ", id ASC LIMIT ? OFFSET ?";
        sql.append(orderBy);
        params.add(request.limit <= 0 ? 100 : request.limit);
        params.add(Math.max(request.offset, 0));
        return database.select(sql.toString(), PgFileRecordRow.class, params.toArray())
            .stream().map(PgFileRecordStore::toRecord).toList();
    }

    @Override
    public long count(FileRecordQuery request) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM ").append(TABLE);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        return database.selectOne(sql.toString(), PgFileRecordCountRow.class, params.toArray())
            .map(row -> row.total).orElse(0L);
    }

    private void conditions(FileRecordQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        if (request.userId != null) {
            where.add("user_id = ?");
            params.add(request.userId);
        }
        addInCondition(where, params, "id", request.ids);
        addInCondition(where, params, "content_hash", request.contentHashes);
        addInCondition(where, params, "file_name", request.fileNames);
        if (request.shareToken != null) {
            where.add("share_token = ?");
            params.add(request.shareToken);
        }
        if (Boolean.TRUE.equals(request.sharedOnly)) where.add("share_token IS NOT NULL");
        if (request.fileNameKeyword != null && !request.fileNameKeyword.isBlank()) {
            where.add("file_name ILIKE ?");
            params.add("%" + request.fileNameKeyword + "%");
        }
        if (request.contentType != null) {
            where.add("content_type = ?");
            params.add(request.contentType);
        }
        if (request.createdAfter != null) {
            where.add("created_at > ?");
            params.add(request.createdAfter);
        }
        if (request.legacyInlineOnly) where.add("1 = 0");
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private void addInCondition(List<String> where, List<Object> params, String column, List<String> values) {
        if (values == null) return;
        if (values.isEmpty()) {
            where.add("1 = 0");
        } else if (values.size() == 1) {
            where.add(column + " = ?");
            params.add(values.getFirst());
        } else {
            where.add(column + " IN (" + placeholders(values.size()) + ")");
            params.addAll(values);
        }
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    @Override
    public void insert(FileRecord record) {
        if (record.data != null && record.storagePath == null) {
            throw new IllegalStateException("inline file content requires object storage in pg mode, fileId=" + record.id);
        }
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
            record.id, record.userId, record.fileName, record.contentType, record.size, record.contentHash,
            record.storagePath, record.thumbStoragePath, record.shareToken, record.sharedAt, record.createdAt);
    }

    @Override
    public boolean insertIfAbsent(FileRecord record) {
        if (record.data != null && record.storagePath == null) {
            throw new IllegalStateException("inline file content requires object storage in pg mode, fileId=" + record.id);
        }
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT DO NOTHING",
            record.id, record.userId, record.fileName, record.contentType, record.size, record.contentHash,
            record.storagePath, record.thumbStoragePath, record.shareToken, record.sharedAt, record.createdAt) > 0;
    }

    @Override
    public void updateThumbStoragePath(String id, String path) {
        database.execute("UPDATE " + TABLE + " SET thumb_storage_path = ?, updated_at = now() WHERE id = ?", path, id);
    }

    @Override
    public void updateFileName(String id, String fileName) {
        database.execute("UPDATE " + TABLE + " SET file_name = ?, updated_at = now() WHERE id = ?", fileName, id);
    }

    @Override
    public void markStored(String id, String storagePath) {
        database.execute("UPDATE " + TABLE + " SET storage_path = ?, updated_at = now() WHERE id = ?", storagePath, id);
    }

    @Override
    public boolean claimShareToken(String id, String userId, String token, ZonedDateTime sharedAt) {
        return database.execute("UPDATE " + TABLE + " SET share_token = ?, shared_at = ?, updated_at = now() "
            + "WHERE id = ? AND user_id = ? AND (share_token IS NULL OR share_token = ?)",
            token, sharedAt, id, userId, "") > 0;
    }

    @Override
    public List<String> sharedUserIds() {
        return database.select("SELECT DISTINCT user_id FROM " + TABLE + " WHERE share_token IS NOT NULL ORDER BY user_id",
                PgFileRecordUserRow.class).stream().map(row -> row.userId).toList();
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }
}
