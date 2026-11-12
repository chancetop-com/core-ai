package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkflowNodeRunStore {
    Optional<WorkflowNodeRun> get(String id);

    boolean insertIfAbsent(WorkflowNodeRun nodeRun);

    void replace(WorkflowNodeRun nodeRun);

    List<WorkflowNodeRun> listByRun(String runId);

    List<WorkflowNodeRun> findWaitingByRun(String runId);

    List<WorkflowNodeRun> findFailedRetryableByRun(String runId);

    List<WorkflowNodeRun> findByRunTypeAndStatus(String runId, String nodeType, NodeRunStatus status);

    int resetRunningByRun(String runId, ZonedDateTime completedAt);

    boolean settleWaiting(String runId, String nodeId, NodeRunStatus status, String output,
                          List<String> chosenEdgeIds, ZonedDateTime completedAt);

    boolean settleWaitingChild(String runId, String nodeId, ChildSettlement settlement);

    record ChildSettlement(String childRunId, NodeRunStatus status, String output, List<ArtifactRef> artifacts,
                           String error, ZonedDateTime completedAt) {
    }
}

