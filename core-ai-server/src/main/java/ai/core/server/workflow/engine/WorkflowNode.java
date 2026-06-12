package ai.core.server.workflow.engine;

import java.util.List;
import java.util.Map;

/**
 * A node in the normalized graph. The planner is node-type agnostic: it only uses {@code id} and topology.
 * {@code type} is a label for the executor registry; {@code name} is the user-facing canvas label used in
 * validation errors; {@code referencedNodeIds} feeds the publish-time dominator check; {@code config} is opaque
 * type-specific configuration (e.g. an AGENT node's agent id) the engine never reads — only the executor does.
 *
 * @author Xander
 */
public record WorkflowNode(String id, String type, String name, List<String> referencedNodeIds, Map<String, Object> config) {
    public WorkflowNode {
        name = name == null || name.isBlank() ? id : name;
        referencedNodeIds = referencedNodeIds == null ? List.of() : List.copyOf(referencedNodeIds);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public WorkflowNode(String id, String type) {
        this(id, type, id, List.of(), Map.of());
    }

    public WorkflowNode(String id, String type, List<String> referencedNodeIds) {
        this(id, type, id, referencedNodeIds, Map.of());
    }

    public WorkflowNode(String id, String type, List<String> referencedNodeIds, Map<String, Object> config) {
        this(id, type, id, referencedNodeIds, config);
    }
}
