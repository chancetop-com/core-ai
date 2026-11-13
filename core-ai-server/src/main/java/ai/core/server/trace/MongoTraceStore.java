package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;
import ai.core.server.trace.domain.TraceStatus;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mongo-backed trace store. The query translation lives here so that adding the PostgreSQL store does
 * not change any caller.
 *
 * @author stephen
 */
public class MongoTraceStore implements TraceStore {
    public static MongoTraceStore of(MongoCollection<Trace> traceCollection) {
        var store = new MongoTraceStore();
        store.traceCollection = traceCollection;
        return store;
    }

    @Inject
    MongoCollection<Trace> traceCollection;

    @Override
    public void insert(Trace trace) {
        traceCollection.insert(trace);
    }

    @Override
    public Trace get(String id, String traceId) {
        if (id != null) {
            var trace = traceCollection.get(id);
            if (trace.isPresent()) return trace.get();
        }
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.limit = 1;
        var results = traceCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<Trace> list(TraceQuery criteria) {
        var query = new Query();
        query.skip = criteria.skip;
        query.limit = criteria.limit;
        query.sort = criteria.ascending ? Sorts.ascending("created_at") : Sorts.descending("created_at");
        var filters = buildFilters(criteria);
        if (!filters.isEmpty()) {
            query.filter = filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        }
        return traceCollection.find(query);
    }

    @Override
    public long count(TraceQuery criteria) {
        var filters = buildFilters(criteria);
        if (filters.isEmpty()) return traceCollection.count(Filters.empty());
        return traceCollection.count(filters.size() == 1 ? filters.getFirst() : Filters.and(filters));
    }

    @Override
    public List<TraceFacetRow> facets(TraceQuery criteria, TraceFacet facet, int limit) {
        if (facet == null) return List.of();
        var filters = buildFilters(criteria);
        // ne(null) also excludes missing fields and keeps clean index bounds
        filters.add(Filters.ne(facet.field, null));
        filters.add(Filters.ne(facet.field, ""));
        var aggregate = new Aggregate<TraceFacetRow>();
        aggregate.resultClass = TraceFacetRow.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(filters)),
            Aggregates.group("$" + facet.field, Accumulators.sum("count", 1)),
            Aggregates.sort(Sorts.descending("count")),
            Aggregates.limit(limit));
        return traceCollection.aggregate(aggregate);
    }

    @Override
    public List<String> traceIds(TraceQuery criteria, int limit) {
        var query = new Query();
        query.limit = limit;
        query.sort = Sorts.descending("created_at");
        var filters = buildFilters(criteria);
        if (!filters.isEmpty()) {
            query.filter = filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        }
        return traceCollection.find(query).stream()
            .map(trace -> trace.traceId)
            .filter(traceId -> traceId != null && !traceId.isEmpty())
            .toList();
    }

    private List<Bson> buildFilters(TraceQuery criteria) {
        List<Bson> filters = new ArrayList<>();
        addIdFilter(filters, criteria);
        if (hasText(criteria.type)) filters.add(Filters.eq("type", criteria.type));
        addSourceFilter(filters, criteria.source);
        if (hasText(criteria.agentName)) filters.add(Filters.eq("agent_name", criteria.agentName));
        if (hasText(criteria.model)) filters.add(Filters.eq("model", criteria.model));
        if (hasText(criteria.status)) filters.add(Filters.eq("status", TraceStatus.valueOf(criteria.status)));
        if (hasText(criteria.sessionId)) filters.add(Filters.eq("session_id", criteria.sessionId));
        if (hasText(criteria.userId)) filters.add(Filters.eq("user_id", criteria.userId));
        // an empty list must stay $in: [] (match nothing); null means "not scoped"
        else if (criteria.userIds != null) filters.add(Filters.in("user_id", criteria.userIds));
        if (criteria.extraUserIds != null) filters.add(Filters.in("user_id", criteria.extraUserIds));
        if (criteria.startFrom != null) filters.add(Filters.gte("started_at", criteria.startFrom));
        if (criteria.startTo != null) filters.add(Filters.lte("started_at", criteria.startTo));
        return filters;
    }

    private void addIdFilter(List<Bson> filters, TraceQuery criteria) {
        if (hasText(criteria.exactId)) {
            filters.add(Filters.or(fullIdClauses(criteria.exactId)));
        } else if (hasText(criteria.idPrefix)) {
            var prefix = "^" + Pattern.quote(criteria.idPrefix.toLowerCase(Locale.ROOT));
            filters.add(Filters.or(Filters.regex("session_id", prefix), Filters.regex("trace_id", prefix)));
        }
    }

    // Server-generated ids are lowercase, but ingested ids (OTLP session.id attributes) are stored
    // verbatim, so the pasted form is matched as well.
    private List<Bson> fullIdClauses(String value) {
        var id = value.toLowerCase(Locale.ROOT);
        List<Bson> clauses = new ArrayList<>();
        clauses.add(Filters.eq("session_id", id));
        clauses.add(Filters.eq("user_id", id));
        clauses.add(Filters.eq("trace_id", id));
        if (!id.equals(value)) {
            clauses.add(Filters.eq("session_id", value));
            clauses.add(Filters.eq("user_id", value));
            clauses.add(Filters.eq("trace_id", value));
        }
        return clauses;
    }

    private void addSourceFilter(List<Bson> filters, String source) {
        if (!hasText(source)) return;
        // Legacy traces predate the source field; treat missing source as "chat"
        if ("chat".equals(source)) {
            filters.add(Filters.or(
                Filters.eq("source", "chat"),
                Filters.exists("source", false),
                Filters.eq("source", null),
                Filters.eq("source", "")));
        } else {
            filters.add(Filters.eq("source", source));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }
}
