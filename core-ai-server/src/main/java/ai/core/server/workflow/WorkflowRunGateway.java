package ai.core.server.workflow;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;

/**
 * Seam between a WORKFLOW node and the workflow run subsystem: start a DECOUPLED child WorkflowRun and link it
 * back to the parent. Unlike {@link AgentRunGateway} there is NO awaitResult — the parent does not block. The
 * child run wakes the parent on its terminal transition (see WorkflowRunner), so the parent holds no lease while
 * the child runs (this is what prevents runner-slot starvation under fan-out).
 *
 * @author Xander
 */
public interface WorkflowRunGateway {
    /** Insert a PENDING child WorkflowRun pinned to the node's referenced published version, stamped with
     *  parentRunId / parentNodeId / childDepth. Returns the child run id. The runner job claims it next tick. */
    String submitChildRun(WorkflowRun parent, WorkflowNode node, String input, int childDepth);

    /** Best-effort recursive cancel of the child run and its descendants (forwarded on parent cancel). */
    void cancelSubtree(String childRunId);
}
