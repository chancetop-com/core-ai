package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkflowRunStore {
    Optional<WorkflowRun> get(String id);

    void insert(WorkflowRun run);

    List<WorkflowRun> findClaimable(ZonedDateTime now);

    List<WorkflowRun> findStalePaused(ZonedDateTime now);

    List<WorkflowRun> findChildren(String parentRunId);

    List<WorkflowRun> findByIds(List<String> ids);

    List<WorkflowRun> listByWorkflow(String workflowId, String userId, boolean admin);

    List<WorkflowRun> find(WorkflowRunQuery query);

    long count(WorkflowRunQuery query);

    boolean claim(String id, String workerId, ZonedDateTime now, ZonedDateTime leaseUntil);

    void markStartedIfNull(String id, ZonedDateTime startedAt);

    boolean renewLease(String id, String workerId, ZonedDateTime leaseUntil);

    boolean pause(String id, String workerId);

    boolean terminate(String id, String workerId, RunTermination termination);

    boolean wakePaused(String id, ZonedDateTime now);

    boolean cancelIfUnfinished(String id);

    record RunTermination(RunStatus status, String error, String output, List<ArtifactRef> artifacts,
                          ZonedDateTime completedAt) {
    }
}

