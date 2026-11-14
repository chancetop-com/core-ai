package ai.core.server.trace.service;

import ai.core.server.trace.domain.TracePayloadBlob;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class MongoTracePayloadRepository implements TracePayloadRepository {
    @Inject
    MongoCollection<TracePayloadBlob> blobCollection;

    @Override
    public boolean store(TracePayloadBlob blob) {
        try {
            blobCollection.insert(blob);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return true; // already stored by an earlier span of the same trace
            throw e;
        }
    }

    @Override
    public List<TracePayloadBlob> findByTrace(String traceId, List<String> blobHashes) {
        if (blobHashes.isEmpty()) return List.of();
        var ids = new ArrayList<String>(blobHashes.size());
        for (var hash : blobHashes) {
            ids.add(traceId + ':' + hash);
        }
        var query = new Query();
        query.filter = Filters.in("_id", ids);
        query.limit = ids.size();
        return blobCollection.find(query);
    }

    @Override
    public long deleteByTraceIds(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return 0;
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += 500) {
            var batch = traceIds.subList(i, Math.min(i + 500, traceIds.size()));
            deleted += blobCollection.delete(Filters.in("trace_id", batch));
        }
        return deleted;
    }
}
