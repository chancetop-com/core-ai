package ai.core.server.trace.service;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.trace.domain.Trace;
import ai.core.server.workflow.WorkflowRunService;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * Trace read authorization shared by the trace views and replay experiments.
 * <p>
 * Grants read access to traces inside the caller's {@link TraceVisibility}, and to readers of the
 * workflow run the trace belongs to (metadata.workflow_run_id). Replay creation must apply
 * exactly the same rules as trace views, so both go through this helper.
 *
 * @author stephen
 */
public class TraceAccessControl {
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;

    public boolean canRead(Trace trace, String userId, TraceVisibility visibility) {
        if (trace == null) return false;
        if (userId == null) return false;
        if (visibility != null && visibility.canSee(trace.userId)) return true;
        if (trace.metadata == null) return false;
        var workflowRunId = trace.metadata.get("workflow_run_id");
        if (workflowRunId == null || workflowRunId.isBlank()) return false;
        return workflowRunCollection.get(workflowRunId)
            .map(run -> WorkflowRunService.canRead(run, userId))
            .orElse(Boolean.FALSE);
    }
}
