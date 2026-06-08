package ai.core.server.workflow;

/**
 * The single effectful surface of the engine: turn a ready node into an outcome. One implementation per node
 * type, dispatched by a registry (P1). The engine never inspects what an executor does. The {@link NodeContext}
 * carries the graph, run, node config, scope path and variable pool — new capabilities extend the context, not
 * this signature.
 *
 * @author Xander
 */
public interface NodeExecutor {
    NodeOutcome execute(NodeContext ctx);
}
