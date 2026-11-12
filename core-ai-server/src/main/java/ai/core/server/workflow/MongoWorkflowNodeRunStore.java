package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;
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
public class MongoWorkflowNodeRunStore implements WorkflowNodeRunStore {
    public static MongoWorkflowNodeRunStore of(MongoCollection<WorkflowNodeRun> collection) {
        var store = new MongoWorkflowNodeRunStore();
        store.nodeRunCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Override
    public Optional<WorkflowNodeRun> get(String id) {
        return nodeRunCollection.get(id);
    }

    @Override
    public boolean insertIfAbsent(WorkflowNodeRun nodeRun) {
        try {
            nodeRunCollection.insert(nodeRun);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(WorkflowNodeRun nodeRun) {
        nodeRunCollection.replace(nodeRun);
    }

    @Override
    public List<WorkflowNodeRun> listByRun(String runId) {
        var query = new Query();
        query.filter = Filters.eq("run_id", runId);
        query.sort = Sorts.ascending("created_at");
        return nodeRunCollection.find(query);
    }

    @Override
    public List<WorkflowNodeRun> findWaitingByRun(String runId) {
        return nodeRunCollection.find(Filters.and(Filters.eq("run_id", runId), Filters.eq("status", NodeRunStatus.WAITING)));
    }

    @Override
    public List<WorkflowNodeRun> findFailedRetryableByRun(String runId) {
        return nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId), Filters.eq("status", NodeRunStatus.FAILED_RETRYABLE)));
    }

    @Override
    public List<WorkflowNodeRun> findByRunTypeAndStatus(String runId, String nodeType, NodeRunStatus status) {
        return nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId), Filters.eq("node_type", nodeType), Filters.eq("status", status)));
    }

    @Override
    public int resetRunningByRun(String runId, ZonedDateTime completedAt) {
        return (int) nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("status", NodeRunStatus.RUNNING)),
            Updates.combine(Updates.set("status", NodeRunStatus.FAILED_RETRYABLE),
                Updates.set("error", "node interrupted: the previous run owner lost its lease or crashed"),
                Updates.set("completed_at", completedAt)));
    }

    @Override
    public boolean settleWaiting(String runId, String nodeId, NodeRunStatus status, String output,
                                 List<String> chosenEdgeIds, ZonedDateTime completedAt) {
        var sets = new ArrayList<Bson>();
        sets.add(Updates.set("status", status));
        sets.add(Updates.set("completed_at", completedAt));
        if (output != null) sets.add(Updates.set("output", output));
        if (chosenEdgeIds != null && !chosenEdgeIds.isEmpty()) sets.add(Updates.set("chosen_edge_ids", chosenEdgeIds));
        return nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", nodeId), Filters.eq("status", NodeRunStatus.WAITING)),
            Updates.combine(sets)) > 0;
    }

    @Override
    public boolean settleWaitingChild(String runId, String nodeId, ChildSettlement settlement) {
        var childRunId = settlement.childRunId();
        var sets = new ArrayList<Bson>();
        sets.add(Updates.set("status", settlement.status()));
        sets.add(Updates.set("completed_at", settlement.completedAt()));
        if (settlement.output() != null) sets.add(Updates.set("output", settlement.output()));
        if (settlement.artifacts() != null && !settlement.artifacts().isEmpty()) {
            sets.add(Updates.set("artifacts", settlement.artifacts()));
        }
        if (settlement.error() != null) sets.add(Updates.set("error", settlement.error()));
        return nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("node_id", nodeId),
                Filters.eq("child_run_id", childRunId), Filters.eq("status", NodeRunStatus.WAITING)),
            Updates.combine(sets)) > 0;
    }
}


