package ai.core.server.workflow.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorTest {
    @Test
    void validLinearGraphHasNoErrors() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("end")),
            List.of(edge("e0", "start", "end")));

        assertTrue(GraphValidator.validate(graph).isEmpty());
    }

    @Test
    void rejectsMultipleEntries() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("a"), node("b"), node("end")),
            List.of(edge("e1", "a", "end"), edge("e2", "b", "end")));

        assertTrue(has(GraphValidator.validate(graph), "exactly one entry"));
    }

    @Test
    void rejectsCycle() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("a"), node("b"), node("end")),
            List.of(edge("e1", "a", "b"), edge("e2", "b", "a"), edge("e3", "b", "end")));

        assertTrue(has(GraphValidator.validate(graph), "acyclic"));
    }

    @Test
    void rejectsDanglingEdge() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("end")),
            List.of(edge("e0", "start", "ghost")));

        assertTrue(has(GraphValidator.validate(graph), "unknown target"));
    }

    @Test
    void rejectsDuplicateNodeId() {
        WorkflowGraph graph = new WorkflowGraph(List.of(node("x"), node("x")), List.of());

        assertTrue(has(GraphValidator.validate(graph), "duplicate node id"));
    }

    @Test
    void rejectsInvalidNodeId() {
        WorkflowGraph graph = new WorkflowGraph(
            List.of(node("start"), node("bad-id")),
            List.of(edge("e0", "start", "bad-id")));

        assertTrue(has(GraphValidator.validate(graph), "invalid node id"));
    }

    private static boolean has(List<String> errors, String fragment) {
        return errors.stream().anyMatch(error -> error.contains(fragment));
    }

    private static WorkflowNode node(String id) {
        return new WorkflowNode(id, id);
    }

    private static WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, target);
    }
}
