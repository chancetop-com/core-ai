package ai.core.server.workflow;

import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the published graph JSON into the engine's immutable {@link WorkflowGraph}. NOTE nodes are canvas-only
 * and excluded. Parsing to a Map tolerates node/edge fields this phase does not yet model (config, position,
 * condition, ...). Selector extraction for the dominator check (P1b) and the variable model (P2) layer on later;
 * this parse keeps only the topology the planner needs.
 *
 * @author Xander
 */
public final class WorkflowGraphParser {
    private WorkflowGraphParser() {
    }

    public static WorkflowGraph parse(String json) {
        Map<String, Object> root = asMap(JSON.fromJSON(Map.class, json));
        List<WorkflowNode> nodes = new ArrayList<>();
        for (Object raw : list(root.get("nodes"))) {
            Map<String, Object> node = asMap(raw);
            String type = string(node.get("type"));
            if (NodeType.NOTE.name().equals(type)) {
                continue;   // canvas-only, never executed
            }
            nodes.add(new WorkflowNode(string(node.get("id")), type));
        }
        List<WorkflowEdge> edges = new ArrayList<>();
        for (Object raw : list(root.get("edges"))) {
            Map<String, Object> edge = asMap(raw);
            edges.add(new WorkflowEdge(string(edge.get("id")), string(edge.get("source")), string(edge.get("target"))));
        }
        return new WorkflowGraph(nodes, edges);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
