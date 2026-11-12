package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonNull;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Locale;

/**
 * @author stephen
 */
public class MongoHubCallStore implements HubCallStore {
    public static MongoHubCallStore of(MongoCollection<HubCall> collection) {
        var store = new MongoHubCallStore();
        store.callCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<HubCall> callCollection;

    @Override
    public void insert(HubCall call) {
        callCollection.insert(call);
    }

    @Override
    public void finish(String callId, long durationMs, Integer outputBytes, boolean success,
                       Integer statusCode, String errorMessage) {
        var updates = new ArrayList<Bson>();
        updates.add(Updates.set("success", success));
        updates.add(Updates.set("is_error", !success));
        updates.add(Updates.set("duration_ms", durationMs));
        updates.add(Updates.set("status_code", statusCode));
        if (outputBytes != null) updates.add(Updates.set("output_bytes", outputBytes));
        if (errorMessage != null) updates.add(Updates.set("error_message", errorMessage));
        callCollection.update(Filters.eq("_id", callId), Updates.combine(updates.toArray(new Bson[0])));
    }

    @Override
    public void attachRun(String callId, String taskId, String contextId, Long inputTokens, Long outputTokens) {
        var updates = new ArrayList<Bson>();
        if (taskId != null) updates.add(Updates.set("task_id", taskId));
        if (contextId != null) updates.add(Updates.set("context_id", contextId));
        if (inputTokens != null) updates.add(Updates.set("input_tokens", inputTokens));
        if (outputTokens != null) updates.add(Updates.set("output_tokens", outputTokens));
        if (updates.isEmpty()) return;
        callCollection.update(Filters.eq("_id", callId), Updates.combine(updates.toArray(new Bson[0])));
    }

    @Override
    public void attachResult(String callId, String refId, Long inputTokens, Long outputTokens) {
        var updates = new ArrayList<Bson>();
        if (refId != null) updates.add(Updates.set("ref_id", refId));
        if (inputTokens != null) updates.add(Updates.set("input_tokens", inputTokens));
        if (outputTokens != null) updates.add(Updates.set("output_tokens", outputTokens));
        if (updates.isEmpty()) return;
        callCollection.update(Filters.eq("_id", callId), Updates.combine(updates.toArray(new Bson[0])));
    }

    @Override
    public HubCallPage list(HubCallListFilter filter) {
        var mongoFilter = filter(filter);
        var total = callCollection.count(mongoFilter);
        var query = new Query();
        query.filter = mongoFilter;
        query.sort = Sorts.descending("created_at");
        query.skip = filter.offset;
        query.limit = filter.limit;
        return new HubCallPage(total, callCollection.find(query));
    }

    @Override
    public int deleteOlderThan(ZonedDateTime cutoff) {
        return (int) callCollection.delete(Filters.lt("created_at", cutoff));
    }

    private Bson filter(HubCallListFilter filter) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.gte("created_at", filter.startFrom));
        if (filter.startTo != null) filters.add(Filters.lte("created_at", filter.startTo));
        if (filter.kind != null && !filter.kind.isBlank()) filters.add(Filters.eq("kind", filter.kind));
        if (filter.userId != null && !filter.userId.isBlank()) filters.add(Filters.eq("user_id", filter.userId));
        if (filter.source != null && !filter.source.isBlank()) filters.add(Filters.eq("source", filter.source));
        if (filter.state != null && !filter.state.isBlank()) {
            filters.add(switch (filter.state.toLowerCase(Locale.ROOT)) {
                case "completed" -> Filters.eq("success", Boolean.TRUE);
                case "failed" -> Filters.eq("success", Boolean.FALSE);
                default -> Filters.eq("success", BsonNull.VALUE);
            });
        }
        return Filters.and(filters);
    }
}
