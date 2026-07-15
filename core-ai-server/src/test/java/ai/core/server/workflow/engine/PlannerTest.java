package ai.core.server.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {
    // ---- helpers ----

    /** Drives plan() to a fixpoint like the runner would, returning the order nodes were dispatched. */
    private static List<String> simulate(WorkflowGraph graph, Map<String, Set<String>> branchChoices) {
        Map<String, NodeFact> state = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        while (true) {
            Frontier f = Planner.plan(graph, new RunState(state));
            if (!f.hasProgress()) {
                break;   // frontier exhausted; the run is done (outputReached() would classify success vs stuck)
            }
            for (String skipped : f.skipNodeIds()) {
                state.put(skipped, NodeFact.skipped(skipped));
            }
            for (String ready : f.readyNodeIds()) {
                order.add(ready);
                Set<String> chosen = branchChoices.get(ready);
                state.put(ready, chosen != null ? NodeFact.completedBranch(ready, chosen) : NodeFact.completedNormal(ready));
            }
        }
        return order;
    }

    private static WorkflowGraph diamond() {
        // a -> merge (ea), b -> merge (eb)
        return graph(
            List.of(node("a"), node("b"), node("merge")),
            List.of(edge("ea", "a", "merge"), edge("eb", "b", "merge")));
    }

    private static WorkflowGraph graph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowGraph(nodes, edges);
    }

    private static WorkflowNode node(String id) {
        return new WorkflowNode(id, id);
    }

    private static WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, target);
    }

    private static RunState facts(NodeFact... factList) {
        Map<String, NodeFact> map = new LinkedHashMap<>(factList.length);
        for (NodeFact fact : factList) {
            map.put(fact.nodeId(), fact);
        }
        return new RunState(map);
    }

    @Test
    void startSeededReadyWhenNoFacts() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("end")),
            List.of(edge("e0", "start", "end")));

        Frontier f = Planner.plan(graph, RunState.empty());

        assertEquals(Set.of("start"), f.readyNodeIds());
        assertTrue(f.skipNodeIds().isEmpty());
        assertFalse(f.outputReached());
    }

    @Test
    void normalCompletionFansOutToAllOutEdges() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("b")),
            List.of(edge("e1", "start", "a"), edge("e2", "start", "b")));

        Frontier f = Planner.plan(graph, facts(NodeFact.completedNormal("start")));

        assertEquals(EdgeVerdict.ACTIVE, f.edgeVerdicts().get("e1"));
        assertEquals(EdgeVerdict.ACTIVE, f.edgeVerdicts().get("e2"));
        assertEquals(Set.of("a", "b"), f.readyNodeIds());   // parallel fan-out is the default
    }

    @Test
    void branchActivatesChosenEdgeAndSkipsSibling() {
        WorkflowGraph graph = graph(
            List.of(node("if"), node("a"), node("b")),
            List.of(edge("e1", "if", "a"), edge("e2", "if", "b")));

        Frontier f = Planner.plan(graph, facts(NodeFact.completedBranch("if", Set.of("e1"))));

        assertEquals(EdgeVerdict.ACTIVE, f.edgeVerdicts().get("e1"));
        assertEquals(EdgeVerdict.SKIPPED, f.edgeVerdicts().get("e2"));
        assertEquals(Set.of("a"), f.readyNodeIds());
        assertEquals(Set.of("b"), f.skipNodeIds());          // sibling whose only in-edge is skipped
    }

    @Test
    void skipPropagatesThroughChain() {
        WorkflowGraph graph = graph(
            List.of(node("if"), node("a"), node("b"), node("c")),
            List.of(edge("e1", "if", "a"), edge("e2", "if", "b"),
                edge("e3", "b", "c")));

        // if chose e1; b was skipped already; c's only in-edge comes from skipped b.
        Frontier f = Planner.plan(graph, facts(
            NodeFact.completedBranch("if", Set.of("e1")),
            NodeFact.skipped("b")));

        assertEquals(EdgeVerdict.SKIPPED, f.edgeVerdicts().get("e3"));
        assertEquals(Set.of("c"), f.skipNodeIds());
        assertFalse(f.readyNodeIds().contains("c"));
    }

    @Test
    void joinFiresWhenOneIncomingActiveOneSkipped() {
        WorkflowGraph graph = diamond();

        // left active (a completed), right skipped (b skipped) -> merge must still fire (no deadlock).
        Frontier f = Planner.plan(graph, facts(
            NodeFact.completedNormal("a"),
            NodeFact.skipped("b")));

        assertEquals(EdgeVerdict.ACTIVE, f.edgeVerdicts().get("ea"));
        assertEquals(EdgeVerdict.SKIPPED, f.edgeVerdicts().get("eb"));
        assertEquals(Set.of("merge"), f.readyNodeIds());
    }

    @Test
    void joinSkippedWhenAllIncomingSkipped() {
        WorkflowGraph graph = diamond();

        Frontier f = Planner.plan(graph, facts(
            NodeFact.skipped("a"),
            NodeFact.skipped("b")));

        assertEquals(Set.of("merge"), f.skipNodeIds());
        assertTrue(f.readyNodeIds().isEmpty());
    }

    @Test
    void nodeNotReadyWhileAnyIncomingPending() {
        WorkflowGraph graph = diamond();

        // only a completed; b still running -> eb PENDING -> merge not ready yet.
        Frontier f = Planner.plan(graph, facts(
            NodeFact.completedNormal("a"),
            NodeFact.running("b")));

        assertEquals(EdgeVerdict.PENDING, f.edgeVerdicts().get("eb"));
        assertFalse(f.readyNodeIds().contains("merge"));
        assertFalse(f.skipNodeIds().contains("merge"));
    }

    @Test
    void outputReachedWhenEndCompletes() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("end")),
            List.of(edge("e0", "start", "end")));

        Frontier f = Planner.plan(graph, facts(
            NodeFact.completedNormal("start"),
            NodeFact.completedNormal("end")));

        assertTrue(f.outputReached());
    }

    @Test
    void parallelSinksBothExecuteWhenOneCompletesEarly() {
        // start fans out to an early sink (endA) and to work -> endB; the run must not stop when endA completes
        // first while endB is still pending. Guards the "completion = frontier exhausted" contract.
        WorkflowGraph graph = graph(
            List.of(node("start"), node("endA"), node("work"), node("endB")),
            List.of(edge("e1", "start", "endA"), edge("e2", "start", "work"), edge("e3", "work", "endB")));

        List<String> order = simulate(graph, Map.of());

        assertTrue(order.contains("endA"));
        assertTrue(order.contains("endB"));   // sibling sink not dropped when the other completes first
    }

    @Test
    void failedSourceKeepsSuccessorNeitherReadyNorSkipped() {
        // The FAILED -> PENDING out-edge path (recovery-critical): a failed node halts its branch and waits.
        WorkflowGraph graph = graph(
            List.of(node("a"), node("b")),
            List.of(edge("e1", "a", "b")));

        Frontier f = Planner.plan(graph, facts(NodeFact.failed("a")));

        assertEquals(EdgeVerdict.PENDING, f.edgeVerdicts().get("e1"));
        assertFalse(f.readyNodeIds().contains("b"));
        assertFalse(f.skipNodeIds().contains("b"));
    }

    @Test
    void diamondConvergesEndToEndWithDeadBranchSkipped() {
        // start -> if -(e_left)-> left -> merge ; if -(e_right)-> right -> merge ; merge -> end
        WorkflowGraph graph = graph(
            List.of(node("start"), node("if"), node("left"), node("right"), node("merge"), node("end")),
            List.of(edge("e0", "start", "if"),
                edge("eL", "if", "left"), edge("eR", "if", "right"),
                edge("mL", "left", "merge"), edge("mR", "right", "merge"),
                edge("eEnd", "merge", "end")));

        List<String> order = simulate(graph, Map.of("if", Set.of("eL")));

        assertEquals("start", order.get(0));
        assertEquals("end", order.get(order.size() - 1));
        assertTrue(order.contains("left"));
        assertFalse(order.contains("right"));           // dead branch skipped, never dispatched
        assertTrue(order.indexOf("left") < order.indexOf("merge"));
        assertTrue(order.indexOf("merge") < order.indexOf("end"));   // join fired despite one skipped arm
    }
}
