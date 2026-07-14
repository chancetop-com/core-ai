package ai.core.server.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Engine-level proof of the resume-from-node contract: seeding a source run's frozen prefix (everything outside the
 * resume node's forward cone) makes the planner derive the resume node as the only ready node. No Mongo — the
 * seeding the service does is exactly "put these terminal facts into RunState", so this validates the core claim.
 *
 * @author Xander
 */
class ResumeFromNodeEngineTest {
    // ---- helpers (mirrors PlannerTest) ----

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
        Map<String, NodeFact> map = new LinkedHashMap<>();
        for (NodeFact fact : factList) {
            map.put(fact.nodeId(), fact);
        }
        return new RunState(map);
    }

    @Test
    void descendantsInclusiveIsForwardCone() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("b"), node("end")),
            List.of(edge("e1", "start", "a"), edge("e2", "a", "b"), edge("e3", "b", "end")));

        assertEquals(Set.of("b", "end"), graph.descendantsInclusive("b"));
        assertEquals(Set.of("start", "a", "b", "end"), graph.descendantsInclusive("start"));
        assertEquals(Set.of("end"), graph.descendantsInclusive("end"));
    }

    @Test
    void resumeFrontierIsThePickedNodeOnLinearGraph() {
        WorkflowGraph graph = graph(
            List.of(node("start"), node("a"), node("b"), node("end")),
            List.of(edge("e1", "start", "a"), edge("e2", "a", "b"), edge("e3", "b", "end")));

        // resume from b -> re-run {b, end}; freeze {start, a} with their completed facts.
        RunState seeded = facts(NodeFact.completedNormal("start"), NodeFact.completedNormal("a"));

        Frontier f = Planner.plan(graph, seeded);

        assertEquals(Set.of("b"), f.readyNodeIds());   // continues from exactly the resume node
    }

    @Test
    void resumeReproducesFrozenBranchChoiceAndSkip() {
        // start -> if -(eL)-> a -> merge ; if -(eR)-> b -> merge ; merge -> end
        WorkflowGraph graph = graph(
            List.of(node("start"), node("if"), node("a"), node("b"), node("merge"), node("end")),
            List.of(edge("e0", "start", "if"),
                edge("eL", "if", "a"), edge("eR", "if", "b"),
                edge("mL", "a", "merge"), edge("mR", "b", "merge"),
                edge("eEnd", "merge", "end")));

        // resume from a -> re-run {a, merge, end}; freeze {start, if, b}. The source took eL, so b was skipped:
        // seeding the branch choice + skip must reproduce a's in-edge as ACTIVE and leave merge waiting on a.
        RunState seeded = facts(
            NodeFact.completedNormal("start"),
            NodeFact.completedBranch("if", Set.of("eL")),
            NodeFact.skipped("b"));

        Frontier f = Planner.plan(graph, seeded);

        assertEquals(Set.of("a"), f.readyNodeIds());
    }
}
