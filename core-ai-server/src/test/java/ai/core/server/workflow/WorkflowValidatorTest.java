package ai.core.server.workflow;

import ai.core.server.workflow.engine.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowValidatorTest {
    @Test
    void validStartToEndPasses() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """);

        assertTrue(WorkflowValidator.validate(graph).isEmpty());
    }

    @Test
    void entryMustBeStart() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "a", "type": "AGENT"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "a", "target": "end"}]}
            """);

        assertTrue(has(WorkflowValidator.validate(graph), "must be START"));
    }

    @Test
    void sinkMustBeEnd() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"}, {"id": "a", "type": "AGENT"}],
             "edges": [{"id": "e0", "source": "start", "target": "a"}]}
            """);

        assertTrue(has(WorkflowValidator.validate(graph), "must be END"));
    }

    @Test
    void unknownNodeTypeRejected() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"}, {"id": "x", "type": "BOGUS"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "x"}, {"id": "e1", "source": "x", "target": "end"}]}
            """);

        assertTrue(has(WorkflowValidator.validate(graph), "unknown type"));
    }

    @Test
    void crossBranchReferenceRejectedAtPublish() {
        // a non-join node (AGENT) reads {{nodes.left.output}} but left is on only one IF/ELSE arm -> rejected
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [
               {"id": "start", "type": "START"},
               {"id": "split", "type": "IF_ELSE"},
               {"id": "left", "type": "AGENT"},
               {"id": "right", "type": "AGENT"},
               {"id": "reader", "type": "AGENT", "config": {"input": "{{nodes.left.output.x}}"}},
               {"id": "end", "type": "END"}],
             "edges": [
               {"id": "e0", "source": "start", "target": "split"},
               {"id": "eL", "source": "split", "target": "left"},
               {"id": "eR", "source": "split", "target": "right"},
               {"id": "mL", "source": "left", "target": "reader"},
               {"id": "mR", "source": "right", "target": "reader"},
               {"id": "eEnd", "source": "reader", "target": "end"}]}
            """);

        assertTrue(has(WorkflowValidator.validate(graph), "Aggregator"));
    }

    @Test
    void aggregatorMayReadConditionalBranchOutput() {
        // the designated join (AGGREGATOR) reads a branch output that doesn't dominate it — allowed (renders empty if skipped)
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [
               {"id": "start", "type": "START"},
               {"id": "split", "type": "IF_ELSE"},
               {"id": "left", "type": "AGENT"},
               {"id": "right", "type": "AGENT"},
               {"id": "merge", "type": "AGGREGATOR", "config": {"output": "{{nodes.left.output.x}}"}},
               {"id": "end", "type": "END"}],
             "edges": [
               {"id": "e0", "source": "start", "target": "split"},
               {"id": "eL", "source": "split", "target": "left"},
               {"id": "eR", "source": "split", "target": "right"},
               {"id": "mL", "source": "left", "target": "merge"},
               {"id": "mR", "source": "right", "target": "merge"},
               {"id": "eEnd", "source": "merge", "target": "end"}]}
            """);

        assertFalse(has(WorkflowValidator.validate(graph), "every path"));
    }

    @Test
    void dominatedReferencePasses() {
        // merge reads {{nodes.split.output}}; split dominates merge (every path passes it) -> valid
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [
               {"id": "start", "type": "START"},
               {"id": "split", "type": "IF_ELSE"},
               {"id": "left", "type": "AGENT"},
               {"id": "right", "type": "AGENT"},
               {"id": "merge", "type": "AGGREGATOR", "config": {"in": "{{nodes.split.output.kind}}"}},
               {"id": "end", "type": "END"}],
             "edges": [
               {"id": "e0", "source": "start", "target": "split"},
               {"id": "eL", "source": "split", "target": "left"},
               {"id": "eR", "source": "split", "target": "right"},
               {"id": "mL", "source": "left", "target": "merge"},
               {"id": "mR", "source": "right", "target": "merge"},
               {"id": "eEnd", "source": "merge", "target": "end"}]}
            """);

        assertTrue(WorkflowValidator.validate(graph).isEmpty());
    }

    private static boolean has(List<String> errors, String fragment) {
        return errors.stream().anyMatch(error -> error.contains(fragment));
    }
}
