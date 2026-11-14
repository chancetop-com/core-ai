package ai.core.server.trace.service;

import ai.core.server.trace.domain.TracePayloadBlob;
import ai.core.server.util.JsonSanitizer;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

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
        database.execute("INSERT INTO " + TABLE + " (trace_id, blob_hash, content) VALUES (?, ?, ?) "
            + "ON CONFLICT DO NOTHING", blob.traceId, hash(blob), JsonSanitizer.text(blob.data));
        return true;
    }

    @Override
    public List<TracePayloadBlob> findByTrace(String traceId, List<String> blobHashes) {
        if (blobHashes.isEmpty()) return List.of();
        var params = new ArrayList<Object>();
        params.add(traceId);
        params.addAll(blobHashes);
        var sql = "SELECT trace_id, blob_hash, content FROM " + TABLE + " WHERE trace_id = ? AND blob_hash IN ("
            + String.join(", ", java.util.Collections.nCopies(blobHashes.size(), "?")) + ")";
        return database.select(sql, PgTracePayloadRow.class, params.toArray()).stream()
            .map(row -> {
                var blob = new TracePayloadBlob();
                blob.traceId = row.traceId;
                blob.id = row.traceId + ':' + row.blobHash;
                blob.data = row.content;
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
}
