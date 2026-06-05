package ai.core.server.workflow.engine;

import java.util.List;

/**
 * A node in the normalized graph. The planner is node-type agnostic: it only uses {@code id} and the
 * graph topology. {@code type} is a label for executors/UI; {@code referencedNodeIds} feeds the
 * publish-time dominator validation (the node ids this node's selectors read).
 *
 * @author Xander
 */
public record WorkflowNode(String id, String type, List<String> referencedNodeIds) {
    public WorkflowNode {
        referencedNodeIds = referencedNodeIds == null ? List.of() : List.copyOf(referencedNodeIds);
    }

    public WorkflowNode(String id, String type) {
        this(id, type, List.of());
    }
}
