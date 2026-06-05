package ai.core.server.workflow;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * The single effectful surface of the engine: turn a ready node into an outcome. One implementation per node
 * type, dispatched by a registry (P1). The engine never inspects what an executor does. Later phases will
 * fold the parameters into a richer NodeContext (variable pool, execution context, sandbox).
 *
 * @author Xander
 */
public interface NodeExecutor {
    NodeOutcome execute(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath);
}
