package ai.core.server.workflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publish-time cross-branch safety: a node Y may read node X's output only if X dominates Y (every path
 * START to Y passes X). This makes a cross-branch reference a publish error (with a fix suggestion) rather
 * than a runtime null, and is the third leg of the triad with runtime skip-propagation and the aggregator
 * node. Computed once and pinned into the published config; zero runtime cost.
 *
 * @author Xander
 */
public final class DominatorValidator {
    private DominatorValidator() {
    }

    /** Iterative dominator sets: Dom(entry) = {entry}; Dom(n) = {n} union intersect(Dom(preds)). */
    public static Map<String, Set<String>> computeDominators(WorkflowGraph graph) {
        Set<String> all = new LinkedHashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            all.add(node.id());
        }
        Map<String, Set<String>> dom = new LinkedHashMap<>();
        for (WorkflowNode node : graph.nodes()) {
            if (graph.inEdges(node.id()).isEmpty()) {
                dom.put(node.id(), new LinkedHashSet<>(Set.of(node.id())));
            } else {
                dom.put(node.id(), new LinkedHashSet<>(all));
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (WorkflowNode node : graph.nodes()) {
                List<WorkflowEdge> ins = graph.inEdges(node.id());
                if (ins.isEmpty()) {
                    continue;
                }
                Set<String> intersect = null;
                for (WorkflowEdge edge : ins) {
                    Set<String> predDom = dom.get(edge.source());
                    if (predDom == null) {
                        continue;
                    }
                    if (intersect == null) {
                        intersect = new LinkedHashSet<>(predDom);
                    } else {
                        intersect.retainAll(predDom);
                    }
                }
                Set<String> newDom = new LinkedHashSet<>();
                newDom.add(node.id());
                if (intersect != null) {
                    newDom.addAll(intersect);
                }
                if (!newDom.equals(dom.get(node.id()))) {
                    dom.put(node.id(), newDom);
                    changed = true;
                }
            }
        }
        return dom;
    }

    /** Returns publish-time errors (empty = valid). */
    public static List<String> validateReferences(WorkflowGraph graph) {
        Map<String, Set<String>> dom = computeDominators(graph);
        List<String> errors = new ArrayList<>();
        for (WorkflowNode y : graph.nodes()) {
            for (String x : y.referencedNodeIds()) {
                if (x.equals(y.id())) {
                    continue;
                }
                if (graph.node(x) == null) {
                    errors.add("node %s references unknown node %s".formatted(y.id(), x));
                    continue;
                }
                if (!dom.getOrDefault(y.id(), Set.of()).contains(x)) {
                    errors.add(("node %s reads %s but %s is not on every path to %s (a branch can skip it) — "
                        + "join the branches with an Aggregator node and read that, or clear the reference")
                        .formatted(y.id(), x, x, y.id()));
                }
            }
        }
        return errors;
    }
}
