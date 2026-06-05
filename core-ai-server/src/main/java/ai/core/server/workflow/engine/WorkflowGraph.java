package ai.core.server.workflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable, normalized graph at one scope level. Builds in/out adjacency once; the planner reads it.
 * Framework-free so the engine core stays unit-testable with zero infrastructure. Assumes the graph passed
 * publish-time structural validation (single START, edges reference existing nodes, acyclic).
 *
 * @author Xander
 */
public final class WorkflowGraph {
    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;
    private final Map<String, WorkflowNode> nodeById;
    private final Map<String, List<WorkflowEdge>> inEdges;
    private final Map<String, List<WorkflowEdge>> outEdges;

    public WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.nodeById = new LinkedHashMap<>();
        this.inEdges = new LinkedHashMap<>();
        this.outEdges = new LinkedHashMap<>();
        for (WorkflowNode node : this.nodes) {
            nodeById.put(node.id(), node);
            inEdges.put(node.id(), new ArrayList<>());
            outEdges.put(node.id(), new ArrayList<>());
        }
        for (WorkflowEdge edge : this.edges) {
            // computeIfAbsent is defensive, not validating: a dangling endpoint creates a phantom adjacency entry
            // rather than failing. Rejecting edges to unknown nodes is the publish-time structural validator's job
            // (a later phase); this constructor assumes that precondition already holds.
            outEdges.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge);
            inEdges.computeIfAbsent(edge.target(), key -> new ArrayList<>()).add(edge);
        }
    }

    public List<WorkflowNode> nodes() {
        return nodes;
    }

    public List<WorkflowEdge> edges() {
        return edges;
    }

    public WorkflowNode node(String id) {
        return nodeById.get(id);
    }

    public List<WorkflowEdge> inEdges(String nodeId) {
        return inEdges.getOrDefault(nodeId, List.of());
    }

    public List<WorkflowEdge> outEdges(String nodeId) {
        return outEdges.getOrDefault(nodeId, List.of());
    }
}
