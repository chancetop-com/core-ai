package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.util.MigrationErrors;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public boolean insertIfAbsent(Trace trace) {
        try {
            traceCollection.insert(trace);
            return true;
        } catch (RuntimeException e) {
            if (MigrationErrors.isDuplicateKey(e)) return false;
            throw e;
        }
    }

    @Override
    public boolean existsByTraceId(String traceId) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.projection = Projections.include("_id");
        query.limit = 1;
        return !traceCollection.find(query).isEmpty();
    }

    @Override
    public Trace findByTraceId(String traceId) {
        var results = traceCollection.find(Filters.eq("trace_id", traceId));
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public void update(TraceUpdate update) {
        var updates = new ArrayList<Bson>();
        if (update.setOnlyWhenEmpty != null) {
            updates.add(Updates.set(field(update.setOnlyWhenEmpty), update.valueWhenEmpty));
        } else {
            if (update.status != null) updates.add(Updates.set("status", update.status));
            if (update.clearErrorMessage) updates.add(Updates.set("error_message", null));
            if (update.errorMessage != null) updates.add(Updates.set("error_message", update.errorMessage));
            if (update.input != null) updates.add(Updates.set("input", update.input));
            if (update.output != null) updates.add(Updates.set("output", update.output));
            if (update.durationMs != null) updates.add(Updates.set("duration_ms", update.durationMs));
            if (update.completedAt != null) updates.add(Updates.set("completed_at", update.completedAt));
            // OTLP upgrades the identity from the root span and writes the columns as they are,
            // including null: the span that created the document may not have carried them.
            var writeNulls = update.writeIdentityNulls;
            if (update.name != null || writeNulls) updates.add(Updates.set("name", update.name));
            if (update.sessionId != null || writeNulls) updates.add(Updates.set("session_id", update.sessionId));
            if (update.userId != null || writeNulls) updates.add(Updates.set("user_id", update.userId));
            if (update.source != null || writeNulls) updates.add(Updates.set("source", update.source));
            if (update.type != null || writeNulls) updates.add(Updates.set("type", update.type));
            if (update.metadata != null) updates.add(Updates.set("metadata", update.metadata));
            if (update.agentName != null || writeNulls) updates.add(Updates.set("agent_name", update.agentName));
            if (update.agentId != null || writeNulls) updates.add(Updates.set("agent_id", update.agentId));
            if (update.model != null) updates.add(Updates.set("model", update.model));
        }
        if (update.inputTokensDelta != null && update.inputTokensDelta != 0) {
            updates.add(Updates.inc("input_tokens", update.inputTokensDelta));
        }
        if (update.outputTokensDelta != null && update.outputTokensDelta != 0) {
            updates.add(Updates.inc("output_tokens", update.outputTokensDelta));
        }
        if (update.totalTokensDelta != null && update.totalTokensDelta != 0) {
            updates.add(Updates.inc("total_tokens", update.totalTokensDelta));
        }
        if (update.cachedTokensDelta != null && update.cachedTokensDelta != 0) {
            updates.add(Updates.inc("cached_tokens", update.cachedTokensDelta));
        }
        if (update.costUsdDelta != null && update.costUsdDelta != 0) {
            updates.add(Updates.inc("cost_usd", update.costUsdDelta));
        }
        if (update.updatedAt != null) updates.add(Updates.set("updated_at", update.updatedAt));
        if (updates.isEmpty()) return;

        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("trace_id", update.traceId));
        if (update.requireStatus != null) filters.add(Filters.eq("status", update.requireStatus));
        if (update.setOnlyWhenEmpty != null) filters.add(emptyOrPlaceholder(update));
        traceCollection.update(Filters.and(filters), Updates.combine(updates));
    }

    @Override
    public void backfillIdentity(String traceId, Map<TraceIdentityField, String> values) {
        if (values == null || values.isEmpty()) return;
        var missing = new ArrayList<Bson>();
        var updates = new ArrayList<Bson>();
        for (var entry : values.entrySet()) {
            var column = identityColumn(entry.getKey());
            missing.add(Filters.or(Filters.exists(column, false), Filters.eq(column, null), Filters.eq(column, "")));
            updates.add(Updates.set(column, entry.getValue()));
        }
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        traceCollection.update(Filters.and(Filters.eq("trace_id", traceId), Filters.or(missing)), Updates.combine(updates));
    }

    private String identityColumn(TraceIdentityField field) {
        return switch (field) {
            case USER_ID -> "user_id";
            case SESSION_ID -> "session_id";
            case AGENT_ID -> "agent_id";
            case AGENT_NAME -> "agent_name";
        };
    }

    private Bson emptyOrPlaceholder(TraceUpdate update) {
        var column = field(update.setOnlyWhenEmpty);
        var empty = Filters.or(Filters.exists(column, false), Filters.eq(column, null), Filters.eq(column, ""));
        if (update.setOnlyWhenEmpty != TraceUpdate.EmptyGuard.NAME || update.namePlaceholders == null) {
            return empty;
        }
        return Filters.or(empty, Filters.in(column, update.namePlaceholders));
    }

    private String field(TraceUpdate.EmptyGuard guard) {
        return switch (guard) {
            case AGENT_NAME -> "agent_name";
            case AGENT_ID -> "agent_id";
            case MODEL -> "model";
            case NAME -> "name";
        };
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
        if (criteria.skip != null) query.skip = criteria.skip;
        if (criteria.limit != null) query.limit = criteria.limit;
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

    @Override
    public long countStartedBefore(ZonedDateTime cutoff) {
        return traceCollection.count(Filters.lt("started_at", cutoff));
    }

    @Override
    public long deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return traceCollection.delete(Filters.in("_id", ids));
    }

    @Override
    public List<TraceAggregateRow> aggregate(TraceAggregation aggregation) {
        var filters = buildFilters(aggregation.query);
        var pipeline = new ArrayList<Bson>();
        pipeline.add(Aggregates.match(filters.isEmpty() ? Filters.empty()
            : filters.size() == 1 ? filters.getFirst() : Filters.and(filters)));
        if (aggregation.latestFirst) pipeline.add(Aggregates.sort(Sorts.descending("started_at")));
        if (aggregation.group == TraceAggregation.Group.DAY_BY_CREATED) {
            pipeline.add(Aggregates.project(Projections.fields(
                Projections.include("total_tokens", "input_tokens", "output_tokens", "cached_tokens", "cost_usd"),
                Projections.computed("day", new Document("$dateToString",
                    new Document("format", "%Y-%m-%d").append("date", "$created_at"))))));
        }
        var accumulators = new ArrayList<BsonField>();
        accumulators.add(Accumulators.sum("input_tokens", "$input_tokens"));
        accumulators.add(Accumulators.sum("output_tokens", "$output_tokens"));
        accumulators.add(Accumulators.sum("total_tokens", "$total_tokens"));
        accumulators.add(Accumulators.sum("cached_tokens", "$cached_tokens"));
        accumulators.add(Accumulators.sum("cost_usd", "$cost_usd"));
        accumulators.add(Accumulators.sum("count", 1L));
        if (aggregation.firstField != null) {
            accumulators.add(Accumulators.first("first_value", firstFieldColumn(aggregation.firstField)));
        }
        pipeline.add(Aggregates.group(groupField(aggregation.group), accumulators));
        if (aggregation.limit > 0) pipeline.add(Aggregates.limit(aggregation.limit));

        var aggregate = new Aggregate<MongoTraceAggregateRow>();
        aggregate.resultClass = MongoTraceAggregateRow.class;
        aggregate.pipeline = pipeline;
        return traceCollection.aggregate(aggregate).stream()
            .map(row -> {
                var value = new TraceAggregateRow();
                value.group = row.group;
                value.firstValue = row.firstValue;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.totalTokens = row.totalTokens;
                value.cachedTokens = row.cachedTokens;
                value.count = row.count;
                value.costUsd = row.costUsd;
                return value;
            })
            .toList();
    }

    private String groupField(TraceAggregation.Group group) {
        return switch (group) {
            case NONE -> null;
            case DAY_BY_CREATED -> "$day";
            case SESSION_ID -> "$session_id";
            case TRACE_ID -> "$trace_id";
            case AGENT_ID -> "$agent_id";
        };
    }

    private String firstFieldColumn(TraceAggregation.FirstField firstField) {
        return switch (firstField) {
            case TRACE_ID -> "$trace_id";
            case AGENT_NAME -> "$agent_name";
        };
    }

    private List<Bson> buildFilters(TraceQuery criteria) {
        List<Bson> filters = new ArrayList<>();
        addIdFilter(filters, criteria);
        if (hasText(criteria.type)) filters.add(Filters.eq("type", criteria.type));
        addSourceFilter(filters, criteria.source);
        if (hasText(criteria.agentName)) filters.add(Filters.eq("agent_name", criteria.agentName));
        if (hasText(criteria.agentId)) filters.add(Filters.eq("agent_id", criteria.agentId));
        if (criteria.agentIds != null) filters.add(Filters.in("agent_id", criteria.agentIds));
        if (hasText(criteria.model)) filters.add(Filters.eq("model", criteria.model));
        if (hasText(criteria.status)) filters.add(Filters.eq("status", TraceStatus.valueOf(criteria.status)));
        if (hasText(criteria.sessionId)) filters.add(Filters.eq("session_id", criteria.sessionId));
        if (criteria.sessionIds != null) filters.add(Filters.in("session_id", criteria.sessionIds));
        if (criteria.traceIds != null) filters.add(Filters.in("trace_id", criteria.traceIds));
        if (criteria.anySessionIds != null || criteria.anyTraceIds != null) {
            var anyOf = new ArrayList<Bson>();
            if (criteria.anySessionIds != null) anyOf.add(Filters.in("session_id", criteria.anySessionIds));
            if (criteria.anyTraceIds != null) anyOf.add(Filters.in("trace_id", criteria.anyTraceIds));
            filters.add(Filters.or(anyOf));
        }
        if (criteria.matchNothing) filters.add(Filters.eq("_id", "__none__"));
        if (criteria.agentIdPresent) {
            filters.add(Filters.ne("agent_id", null));
            filters.add(Filters.ne("agent_id", ""));
        }
        if (hasText(criteria.userId)) filters.add(Filters.eq("user_id", criteria.userId));
        // an empty list must stay $in: [] (match nothing); null means "not scoped"
        else if (criteria.userIds != null) filters.add(Filters.in("user_id", criteria.userIds));
        if (criteria.extraUserIds != null) filters.add(Filters.in("user_id", criteria.extraUserIds));
        if (criteria.createdFrom != null) filters.add(Filters.gte("created_at", criteria.createdFrom));
        if (criteria.createdTo != null) filters.add(Filters.lte("created_at", criteria.createdTo));
        if (criteria.startFrom != null) filters.add(Filters.gte("started_at", criteria.startFrom));
        if (criteria.startAfter != null) filters.add(Filters.gt("started_at", criteria.startAfter));
        if (criteria.startTo != null) filters.add(Filters.lte("started_at", criteria.startTo));
        if (criteria.startBefore != null) filters.add(Filters.lt("started_at", criteria.startBefore));
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
