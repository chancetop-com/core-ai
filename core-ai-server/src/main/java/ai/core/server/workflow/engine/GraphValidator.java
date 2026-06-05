package ai.core.server.workflow.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Publish-time structural validation of a graph: the topology rules the planner assumes (and never re-checks
 * at run time). Type-agnostic and pure — START/END type assertions and reference (dominator) checks live in
 * the workflow layer. Returns the list of errors (empty = valid).
 *
 * @author Xander
 */
public final class GraphValidator {
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private GraphValidator() {
    }

    public static List<String> validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph.nodes().isEmpty()) {
            errors.add("graph has no nodes");
            return errors;
        }

        Set<String> ids = new HashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            if (node.id() == null || !NODE_ID.matcher(node.id()).matches()) {
                errors.add("invalid node id: " + node.id());
            }
            if (!ids.add(node.id())) {
                errors.add("duplicate node id: " + node.id());
            }
        }

        boolean dangling = false;
        for (WorkflowEdge edge : graph.edges()) {
            if (graph.node(edge.source()) == null) {
                errors.add("edge " + edge.id() + " has unknown source: " + edge.source());
                dangling = true;
            }
            if (graph.node(edge.target()) == null) {
                errors.add("edge " + edge.id() + " has unknown target: " + edge.target());
                dangling = true;
            }
        }

        List<String> entries = new ArrayList<>();
        List<String> sinks = new ArrayList<>();
        for (WorkflowNode node : graph.nodes()) {
            if (graph.inEdges(node.id()).isEmpty()) {
                entries.add(node.id());
            }
            if (graph.outEdges(node.id()).isEmpty()) {
                sinks.add(node.id());
            }
        }
        if (entries.size() != 1) {
            errors.add("graph must have exactly one entry (no-in-edge) node, found " + entries.size() + ": " + entries);
        }
        if (sinks.isEmpty()) {
            errors.add("graph must have at least one sink (no-out-edge) node");
        }
        if (hasCycle(graph)) {
            errors.add("graph must be acyclic");
        }

        // reachability only makes sense with a single entry and no dangling edges
        if (entries.size() == 1 && !dangling) {
            Set<String> fromEntry = reachableForward(graph, entries.get(0));
            Set<String> toSink = reachableBackward(graph, sinks);
            for (WorkflowNode node : graph.nodes()) {
                if (!fromEntry.contains(node.id())) {
                    errors.add("node " + node.id() + " is not reachable from the entry");
                }
                if (!toSink.contains(node.id())) {
                    errors.add("node " + node.id() + " cannot reach any sink");
                }
            }
        }
        return errors;
    }

    private static boolean hasCycle(WorkflowGraph graph) {
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            if (dfsCycle(graph, node.id(), visited, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfsCycle(WorkflowGraph graph, String nodeId, Set<String> visited, Set<String> stack) {
        if (stack.contains(nodeId)) {
            return true;
        }
        if (!visited.add(nodeId)) {
            return false;
        }
        stack.add(nodeId);
        for (WorkflowEdge edge : graph.outEdges(nodeId)) {
            if (graph.node(edge.target()) != null && dfsCycle(graph, edge.target(), visited, stack)) {
                return true;
            }
        }
        stack.remove(nodeId);
        return false;
    }

    private static Set<String> reachableForward(WorkflowGraph graph, String start) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<String>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (WorkflowEdge edge : graph.outEdges(queue.poll())) {
                if (graph.node(edge.target()) != null && seen.add(edge.target())) {
                    queue.add(edge.target());
                }
            }
        }
        return seen;
    }

    private static Set<String> reachableBackward(WorkflowGraph graph, List<String> sinks) {
        Set<String> seen = new LinkedHashSet<>(sinks);
        var queue = new ArrayDeque<>(sinks);
        while (!queue.isEmpty()) {
            for (WorkflowEdge edge : graph.inEdges(queue.poll())) {
                if (graph.node(edge.source()) != null && seen.add(edge.source())) {
                    queue.add(edge.source());
                }
            }
        }
        return seen;
    }
}
