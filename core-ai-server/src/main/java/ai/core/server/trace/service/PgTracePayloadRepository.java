package ai.core.server.trace.service;

import ai.core.server.trace.domain.TracePayloadBlob;
import ai.core.server.trace.PgTraceCountRow;
import ai.core.server.util.JsonSanitizer;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author stephen
 */
public class PgTracePayloadRepository implements TracePayloadRepository {
    private static final String TABLE = "app.trace_payloads";
    private static final int DELETE_BATCH_SIZE = 500;

    private static String hash(TracePayloadBlob blob) {
        return blob.id.substring(blob.traceId.length() + 1);
    }

    @Inject
    Database database;

    @Override
    public boolean store(TracePayloadBlob blob) {
        database.execute("INSERT INTO " + TABLE
            + " (trace_id, blob_hash, content, object_hash, storage_key, content_type, size_bytes)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            blob.traceId, hash(blob), JsonSanitizer.text(blob.data), blob.objectHash, blob.storageKey,
            blob.contentType, blob.sizeBytes);
        return true;
    }

    @Override
    public List<TracePayloadBlob> findByTrace(String traceId, List<String> blobHashes) {
        if (blobHashes.isEmpty()) return List.of();
        var params = new ArrayList<Object>();
        params.add(traceId);
        params.addAll(blobHashes);
        var sql = "SELECT trace_id, blob_hash, content, object_hash, storage_key, content_type, size_bytes FROM " + TABLE
            + " WHERE trace_id = ? AND blob_hash IN ("
            + String.join(", ", java.util.Collections.nCopies(blobHashes.size(), "?")) + ")";
        return database.select(sql, PgTracePayloadRow.class, params.toArray()).stream()
            .map(row -> {
                var blob = new TracePayloadBlob();
                blob.traceId = row.traceId;
                blob.id = row.traceId + ':' + row.blobHash;
                blob.data = row.content;
                blob.objectHash = row.objectHash;
                blob.storageKey = row.storageKey;
                blob.contentType = row.contentType;
                blob.sizeBytes = row.sizeBytes;
                return blob;
            })
            .toList();
    }

    @Override
    public long deleteByTraceIds(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return 0;
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += DELETE_BATCH_SIZE) {
            var batch = traceIds.subList(i, Math.min(i + DELETE_BATCH_SIZE, traceIds.size()));
            deleted += database.execute("DELETE FROM " + TABLE + " WHERE trace_id IN ("
                + String.join(", ", java.util.Collections.nCopies(batch.size(), "?")) + ")", batch.toArray());
        }
        return deleted;
    }

    @Override
    public List<String> deleteByTraceIdsReturningStorageKeys(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        var keys = new ArrayList<String>();
        for (int i = 0; i < traceIds.size(); i += DELETE_BATCH_SIZE) {
            var batch = traceIds.subList(i, Math.min(i + DELETE_BATCH_SIZE, traceIds.size()));
            var sql = "DELETE FROM " + TABLE + " WHERE trace_id IN ("
                + String.join(", ", java.util.Collections.nCopies(batch.size(), "?")) + ") RETURNING storage_key";
            database.select(sql, PgTraceStorageKeyRow.class, batch.toArray()).stream()
                .map(row -> row.storageKey)
                .filter(Objects::nonNull)
                .forEach(keys::add);
        }
        return keys.stream().distinct().toList();
    }

    @Override
    public boolean hasStorageKey(String storageKey) {
        var row = database.selectOne("SELECT count(*) AS count FROM " + TABLE + " WHERE storage_key = ? LIMIT 1",
            PgTraceCountRow.class, storageKey).orElse(null);
        return row != null && row.count != null && row.count > 0;
    }
}
