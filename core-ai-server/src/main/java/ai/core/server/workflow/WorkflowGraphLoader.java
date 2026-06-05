package ai.core.server.workflow;

import ai.core.server.workflow.engine.WorkflowGraph;

/**
 * Loads the immutable, sha256-pinned graph for a published workflow version. The implementation (reading
 * WorkflowPublishedVersion and parsing the graph JSON) lands in P1; the runner depends only on this seam.
 *
 * @author Xander
 */
public interface WorkflowGraphLoader {
    WorkflowGraph load(String versionId);
}
