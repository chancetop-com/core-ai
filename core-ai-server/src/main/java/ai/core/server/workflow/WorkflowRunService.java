package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Creates and reads workflow runs. createRun pins the latest published version and inserts a PENDING run with
 * lease_until=now so the WorkflowRunnerJob claims and drives it on the next tick (same path as crash recovery).
 *
 * @author Xander
 */
public class WorkflowRunService {
    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    public WorkflowRun createRun(String workflowId, String input, TriggerType triggeredBy, String userId) {
        WorkflowDefinition definition = definitionCollection.get(workflowId)
            .orElseThrow(() -> new NotFoundException("workflow not found: " + workflowId));
        if (!definition.userId.equals(userId)) {
            throw new ForbiddenException("workflow does not belong to the current user: " + workflowId);
        }
        if (definition.publishedVersionId == null) {
            throw new BadRequestException("workflow is not published: " + workflowId);
        }
        WorkflowPublishedVersion version = versionCollection.get(definition.publishedVersionId)
            .orElseThrow(() -> new IllegalStateException("published version missing: " + definition.publishedVersionId));

        var now = ZonedDateTime.now();
        var run = new WorkflowRun();
        run.id = UUID.randomUUID().toString();
        run.workflowId = workflowId;
        run.versionId = version.id;
        run.versionSha256 = version.sha256;
        run.userId = userId;
        run.mode = definition.mode;
        run.triggeredBy = triggeredBy;
        run.status = RunStatus.PENDING;
        run.input = input;
        run.leaseUntil = now;   // claimable immediately by the runner job
        run.createdAt = now;
        runCollection.insert(run);
        return run;
    }

    public WorkflowRun getRun(String runId, String userId) {
        WorkflowRun run = runCollection.get(runId)
            .orElseThrow(() -> new NotFoundException("workflow run not found: " + runId));
        if (!run.userId.equals(userId)) {
            throw new ForbiddenException("workflow run does not belong to the current user: " + runId);
        }
        return run;
    }

    public List<WorkflowRun> listRuns(String workflowId, String userId) {
        return runCollection.find(Filters.and(
            Filters.eq("workflow_id", workflowId),
            Filters.eq("user_id", userId)));
    }

    public List<WorkflowNodeRun> listNodeRuns(String runId, String userId) {
        getRun(runId, userId);   // ownership check
        return nodeRunCollection.find(Filters.eq("run_id", runId));
    }
}
