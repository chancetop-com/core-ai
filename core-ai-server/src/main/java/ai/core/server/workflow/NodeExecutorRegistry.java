package ai.core.server.workflow;

import java.util.Map;

/**
 * Dispatches a node to its type-specific executor — the engine seam stays a single {@link NodeExecutor} while
 * each node type plugs in here. Mirrors ToolRefResolver's dispatch by ToolSourceType: adding a node type =
 * registering one executor; the engine, planner and runner are untouched.
 *
 * @author Xander
 */
public class NodeExecutorRegistry implements NodeExecutor {
    private final Map<NodeType, NodeExecutor> executors;

    public NodeExecutorRegistry(Map<NodeType, NodeExecutor> executors) {
        this.executors = Map.copyOf(executors);
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        NodeType type = NodeType.of(ctx.node().type());
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalStateException("no executor registered for node type " + type + " (node " + ctx.node().id() + ")");
        }
        return executor.execute(ctx);
    }
}
