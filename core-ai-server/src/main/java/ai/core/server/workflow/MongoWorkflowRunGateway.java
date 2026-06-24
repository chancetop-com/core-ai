package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Production {@link WorkflowRunGateway}: a WORKFLOW node starts a DECOUPLED child WorkflowRun pinned to the
 * referenced published version (immutable -> the frozen snapshot). The child row is inserted PENDING with
 * lease_until=now so the WorkflowRunnerJob claims it next tick (same path as createRun / crash recovery). The
 * parent does not block; the child's terminal transition wakes it (WorkflowRunner). Cascade-cancel walks the
 * parent_run_id tree.
 *
 * @author Xander
 */
public class MongoWorkflowRunGateway implements WorkflowRunGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoWorkflowRunGateway.class);

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Override
    public String submitChildRun(WorkflowRun parent, WorkflowNode node, String input, int childDepth) {
        String versionId = String.valueOf(node.config().get("version_id"));
        WorkflowPublishedVersion version = versionCollection.get(versionId)
            .orElseThrow(() -> new IllegalStateException("referenced workflow version not found: " + versionId));
        String sourceWorkflowId = String.valueOf(node.config().get("source_workflow_id"));
        if (!version.workflowId.equals(sourceWorkflowId)) {
            throw new IllegalStateException("workflow node version " + versionId + " does not belong to workflow " + sourceWorkflowId);
        }
        var now = ZonedDateTime.now();
        var run = new WorkflowRun();
        run.id = UUID.randomUUID().toString();
        run.workflowId = sourceWorkflowId;
        run.versionId = version.id;
        run.versionSha256 = version.sha256;
        run.userId = parent.userId;   // attributed to the parent's owner; published version is public to run
        run.mode = parent.mode;
        run.triggeredBy = TriggerType.WORKFLOW;
        run.status = RunStatus.PENDING;
        run.input = input;
        run.preview = Boolean.TRUE.equals(parent.preview);
        run.parentRunId = parent.id;
        run.parentNodeId = node.id();
        run.depth = childDepth;
        run.leaseUntil = now;
        run.createdAt = now;
        runCollection.insert(run);
        LOGGER.info("submitted child workflow run, childRunId={}, parentRunId={}, versionId={}", run.id, parent.id, version.id);
        return run.id;
    }

    @Override
    public void cancelSubtree(String childRunId) {
        // best-effort: mark this child CANCELLED (its driver polls status and stops; its terminal then settles the
        // parent node), then recurse into its own children. Guarded on non-terminal so a finished run is untouched.
        WorkflowRun child = runCollection.get(childRunId).orElse(null);
        if (child == null) {
            return;
        }
        runCollection.update(
            Filters.and(Filters.eq("_id", childRunId), Filters.eq("completed_at", null)),
            Updates.set("status", RunStatus.CANCELLED));
        for (WorkflowRun grandchild : runCollection.find(Filters.eq("parent_run_id", childRunId))) {
            cancelSubtree(grandchild.id);
        }
    }
}
