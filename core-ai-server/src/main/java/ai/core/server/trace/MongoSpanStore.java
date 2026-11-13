package ai.core.server.trace;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class MongoSpanStore implements SpanStore {
    private static final int DELETE_BATCH_SIZE = 500;

    public static MongoSpanStore of(MongoCollection<Span> collection) {
        var store = new MongoSpanStore();
        store.spanCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<Span> spanCollection;

    @Override
    public void insert(Span span) {
        spanCollection.insert(span);
    }

    @Override
    public Span find(String traceId, String spanId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("trace_id", traceId), Filters.eq("span_id", spanId));
        query.limit = 1;
        var results = spanCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public boolean existsToolCall(String traceId, String toolCallId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("trace_id", traceId), Filters.eq("tool_call_id", toolCallId));
        query.projection = Projections.include("_id");
        query.limit = 1;
        return !spanCollection.find(query).isEmpty();
    }

    @Override
    public List<Span> summariesByTrace(String traceId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.sort = Sorts.ascending("started_at");
        query.limit = limit;
        query.projection = Projections.exclude("input", "output");
        return spanCollection.find(query);
    }

    @Override
    public List<Span> summariesByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.in("trace_id", traceIds);
        query.limit = limit;
        query.projection = Projections.exclude("input", "output");
        return spanCollection.find(query);
    }

    @Override
    public List<Span> llmSpansByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.and(Filters.in("trace_id", traceIds), Filters.eq("type", SpanType.LLM.name()));
        query.sort = Sorts.ascending("started_at");
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public List<Span> generations(int offset, int limit, String model, List<String> userIds, List<String> traceIds) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("started_at");

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("type", SpanType.LLM));
        if (model != null && !model.isEmpty()) {
            filters.add(Filters.eq("model", model));
        }
        if (userIds != null) {
            if (traceIds.isEmpty()) {
                filters.add(Filters.in("user_id", userIds));
            } else {
                filters.add(Filters.or(Filters.in("user_id", userIds), Filters.in("trace_id", traceIds)));
            }
        }
        query.filter = Filters.and(filters);
        return spanCollection.find(query);
    }

    @Override
    public List<Span> before(ZonedDateTime cutoff, int offset, int limit) {
        var query = new Query();
        query.filter = Filters.lt("started_at", cutoff);
        query.sort = Sorts.ascending("started_at");
        query.skip = offset;
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public List<Span> pageByTrace(String traceId, int offset, int limit) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.skip = offset;
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public long countBefore(ZonedDateTime cutoff) {
        return spanCollection.count(Filters.lt("started_at", cutoff));
    }

    @Override
    public long deleteByTraceIds(List<String> traceIds) {
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += DELETE_BATCH_SIZE) {
            var batch = traceIds.subList(i, Math.min(i + DELETE_BATCH_SIZE, traceIds.size()));
            deleted += spanCollection.delete(Filters.in("trace_id", batch));
        }
        return deleted;
    }

    @Override
    public long deleteByIds(List<String> ids) {
        long deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            var batch = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
            deleted += spanCollection.delete(Filters.in("_id", batch));
        }
        return deleted;
    }
}
