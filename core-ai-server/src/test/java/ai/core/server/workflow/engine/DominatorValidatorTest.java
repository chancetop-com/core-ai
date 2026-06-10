package ai.core.server.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DominatorValidatorTest {
    @Test
    void startDominatesAllNodes() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("a"), node("end")),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "end")));

        var dom = DominatorValidator.computeDominators(graph);

        assertTrue(dom.get("a").contains("start"));
        assertTrue(dom.get("end").contains("start"));
        assertTrue(dom.get("end").contains("a"));   // linear chain: a dominates end
    }

    @Test
    void linearUpstreamReferenceIsValid() {
        // start -> a -> b ; b reads a (a dominates b)
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("a"), node("b", List.of("a"))),
            List.of(edge("e0", "start", "a"), edge("e1", "a", "b")));

        assertTrue(DominatorValidator.validateReferences(graph).isEmpty());
    }

    @Test
    void crossBranchReferenceIsRejected() {
        // start -> if -> left -> join ; if -> right -> join ; join reads left (left does NOT dominate join)
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("if"), node("left"), node("right"), node("join", List.of("left"))),
            List.of(edge("e0", "start", "if"),
                edge("eL", "if", "left"), edge("eR", "if", "right"),
                edge("jL", "left", "join"), edge("jR", "right", "join")));

        List<String> errors = DominatorValidator.validateReferences(graph);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Aggregator"));
    }

    @Test
    void referenceToAggregatorThatDominatesJoinIsValid() {
        // both branches feed an aggregator that dominates the consumer -> reading the aggregator is safe
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("if"), node("left"), node("right"), node("agg"), node("end", List.of("agg"))),
            List.of(edge("e0", "start", "if"),
                edge("eL", "if", "left"), edge("eR", "if", "right"),
                edge("aL", "left", "agg"), edge("aR", "right", "agg"),
                edge("eEnd", "agg", "end")));

        assertTrue(DominatorValidator.validateReferences(graph).isEmpty());
    }

    @Test
    void unknownReferenceIsRejected() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("a", List.of("ghost"))),
            List.of(edge("e0", "start", "a")));

        List<String> errors = DominatorValidator.validateReferences(graph);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("unknown node ghost"));
    }

    private static WorkflowNode node(String id) {
        return new WorkflowNode(id, id);
    }

    private static WorkflowNode node(String id, List<String> referencedNodeIds) {
        return new WorkflowNode(id, id, referencedNodeIds);
    }

    private static WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, target);
    }
}
