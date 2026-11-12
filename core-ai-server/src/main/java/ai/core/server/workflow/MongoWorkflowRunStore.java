package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoWorkflowRunStore implements WorkflowRunStore {
    public static MongoWorkflowRunStore of(MongoCollection<WorkflowRun> collection) {
        var store = new MongoWorkflowRunStore();
        store.runCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Override
    public Optional<WorkflowRun> get(String id) {
        return runCollection.get(id);
    }

    @Override
    public void insert(WorkflowRun run) {
        runCollection.insert(run);
    }

    @Override
    public List<WorkflowRun> findClaimable(ZonedDateTime now) {
        return runCollection.find(Filters.and(
            Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
            Filters.lte("lease_until", now)));
    }

    @Override
    public List<WorkflowRun> findStalePaused(ZonedDateTime now) {
        return runCollection.find(Filters.and(Filters.eq("status", RunStatus.PAUSED), Filters.lte("lease_until", now)));
    }

    @Override
    public List<WorkflowRun> findChildren(String parentRunId) {
        return runCollection.find(Filters.eq("parent_run_id", parentRunId));
    }

    @Override
    public List<WorkflowRun> findByIds(List<String> ids) {
        return ids.isEmpty() ? List.of() : runCollection.find(Filters.in("_id", ids));
    }

    @Override
    public List<WorkflowRun> listByWorkflow(String workflowId, String userId, boolean admin) {
        if (admin) return runCollection.find(Filters.eq("workflow_id", workflowId));
        var query = new Query();
        query.filter = Filters.and(Filters.eq("workflow_id", workflowId),
            Filters.or(Filters.eq("visibility", WorkflowVisibility.PUBLIC), Filters.eq("user_id", userId)));
        query.sort = Sorts.descending("created_at");
        return runCollection.find(query);
    }

    @Override
    public List<WorkflowRun> find(WorkflowRunQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = request.ascending ? Sorts.ascending("started_at") : Sorts.descending("started_at");
        query.skip = Math.max(0, request.offset);
        query.limit = Math.min(Math.max(request.limit, 1), 1000);
        return runCollection.find(query);
    }

    @Override
    public long count(WorkflowRunQuery request) {
        return runCollection.count(filter(request));
    }

    private Bson filter(WorkflowRunQuery request) {
        var filters = new java.util.ArrayList<Bson>();
        if (request.workflowIds != null) {
            if (request.workflowIds.isEmpty()) return Filters.eq("_id", "__none__");
            filters.add(Filters.in("workflow_id", request.workflowIds));
        }
        if (request.ids != null) {
            if (request.ids.isEmpty()) return Filters.eq("_id", "__none__");
            filters.add(Filters.in("_id", request.ids));
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) filters.add(Filters.nin("_id", request.excludeIds));
        if (request.from != null) filters.add(request.exclusiveFrom ? Filters.gt("started_at", request.from) : Filters.gte("started_at", request.from));
        if (!request.includePreview) filters.add(Filters.or(Filters.exists("preview", false), Filters.eq("preview", Boolean.FALSE)));
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }

    @Override
    public boolean claim(String id, String workerId, ZonedDateTime now, ZonedDateTime leaseUntil) {
        return runCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
                Filters.lte("lease_until", now)),
            Updates.combine(Updates.set("claimed_by", workerId), Updates.set("status", RunStatus.RUNNING),
                Updates.set("lease_until", leaseUntil))) > 0;
    }

    @Override
    public void markStartedIfNull(String id, ZonedDateTime startedAt) {
        runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("started_at", null)),
            Updates.set("started_at", startedAt));
    }

    @Override
    public boolean renewLease(String id, String workerId, ZonedDateTime leaseUntil) {
        return runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("claimed_by", workerId)),
            Updates.set("lease_until", leaseUntil)) > 0;
    }

    @Override
    public boolean pause(String id, String workerId) {
        return runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("claimed_by", workerId),
            Filters.eq("status", RunStatus.RUNNING)), Updates.set("status", RunStatus.PAUSED)) > 0;
    }

    @Override
    public boolean terminate(String id, String workerId, RunTermination termination) {
        var status = termination.status();
        var error = termination.error();
        var output = termination.output();
        var artifacts = termination.artifacts();
        var completedAt = termination.completedAt();
        var sets = new java.util.ArrayList<Bson>();
        sets.add(Updates.set("status", status));
        sets.add(Updates.set("completed_at", completedAt));
        if (error != null) sets.add(Updates.set("error", error));
        if (output != null) sets.add(Updates.set("output", output));
        if (artifacts != null && !artifacts.isEmpty()) sets.add(Updates.set("artifacts", artifacts));
        return runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("claimed_by", workerId),
            Filters.eq("completed_at", null)), Updates.combine(sets)) > 0;
    }

    @Override
    public boolean wakePaused(String id, ZonedDateTime now) {
        return runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("status", RunStatus.PAUSED)),
            Updates.combine(Updates.set("status", RunStatus.PENDING), Updates.set("lease_until", now))) > 0;
    }

    @Override
    public boolean cancelIfUnfinished(String id) {
        return runCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq("completed_at", null)),
            Updates.set("status", RunStatus.CANCELLED)) > 0;
    }
}

