package ai.core.server.run;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoAgentRunStore implements AgentRunStore {
    public static MongoAgentRunStore of(MongoCollection<AgentRun> collection) {
        var store = new MongoAgentRunStore();
        store.agentRunCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Override
    public Optional<AgentRun> get(String id) {
        return agentRunCollection.get(id);
    }

    @Override
    public void insert(AgentRun run) {
        agentRunCollection.insert(run);
    }

    @Override
    public void replace(AgentRun run) {
        agentRunCollection.replace(run);
    }

    @Override
    public List<AgentRun> find(AgentRunQuery request) {
        return agentRunCollection.find(toQuery(request));
    }

    @Override
    public long count(AgentRunQuery request) {
        return agentRunCollection.count(filter(request));
    }

    @Override
    public Optional<AgentRun> findOne(AgentRunQuery request) {
        request.limit = 1;
        var runs = find(request);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.getFirst());
    }

    @Override
    public boolean claimTraceId(String id, String traceId) {
        return agentRunCollection.update(
            Filters.and(Filters.eq("_id", id),
                Filters.or(Filters.exists("trace_id", false), Filters.eq("trace_id", null), Filters.eq("trace_id", ""))),
            Updates.set("trace_id", traceId)) > 0;
    }

    @Override
    public boolean failIfUnfinished(String id, String error, ZonedDateTime completedAt) {
        return agentRunCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.in("status", RunStatus.RUNNING, RunStatus.PENDING)),
            Updates.combine(
                Updates.set("status", RunStatus.FAILED),
                Updates.set("error", error),
                Updates.set("completed_at", completedAt))) > 0;
    }

    @Override
    public boolean reconcileIfUnfinished(String id, boolean completed, String output, String error, ZonedDateTime completedAt) {
        return agentRunCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.in("status", RunStatus.RUNNING, RunStatus.PENDING)),
            Updates.combine(
                Updates.set("status", completed ? RunStatus.COMPLETED : RunStatus.FAILED),
                Updates.set("output", completed ? output : null),
                Updates.set("error", completed ? null : error),
                Updates.set("completed_at", completedAt))) > 0;
    }

    private Query toQuery(AgentRunQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = request.ascending ? Sorts.ascending(request.sortField) : Sorts.descending(request.sortField);
        query.skip = request.offset;
        query.limit = request.limit;
        return query;
    }

    private Bson filter(AgentRunQuery request) {
        var filters = new ArrayList<Bson>();
        if (request.agentId != null) filters.add(Filters.eq("agent_id", request.agentId));
        if (request.agentIds != null) {
            filters.add(request.agentIds.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("agent_id", request.agentIds));
        }
        if (request.userId != null) filters.add(Filters.eq("user_id", request.userId));
        if (request.scheduleId != null) filters.add(Filters.eq("schedule_id", request.scheduleId));
        if (request.ids != null) {
            filters.add(request.ids.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("_id", request.ids));
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) filters.add(Filters.nin("_id", request.excludeIds));
        if (request.status != null) filters.add(Filters.eq("status", request.status));
        if (request.statuses != null && !request.statuses.isEmpty()) filters.add(Filters.in("status", request.statuses));
        if (request.from != null) filters.add(Filters.gte(request.fromField, request.from));
        if (request.to != null) filters.add(Filters.lt(request.fromField, request.to));
        if (request.hasArtifacts) filters.add(Filters.exists("artifacts.0", true));
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }
}


