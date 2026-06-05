package ai.core.server.workflow.engine;

import java.util.Map;

/**
 * The durable facts the planner folds: the node-runs that exist at one scope, keyed by node id. Absence of
 * an entry means the node has not started. This is a projection of the persisted WorkflowNodeRun set, not
 * an independently stored object.
 *
 * @author Xander
 */
public record RunState(Map<String, NodeFact> facts) {
    public RunState {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public static RunState empty() {
        return new RunState(Map.of());
    }

    public NodeFact factOf(String nodeId) {
        return facts.get(nodeId);
    }
}
