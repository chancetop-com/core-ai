package ai.core.server.workflow;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * Everything a node executor needs to run: the static graph + node config, the run, the scope path, and the
 * variable pool (upstream node outputs + run input). Folding the positional parameters into one context means
 * adding a new capability later (e.g. an execution context / sandbox handle) is one field here, not a signature
 * change rippled across every executor.
 *
 * @author Xander
 */
public record NodeContext(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, VariablePool pool) {
}
