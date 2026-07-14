package ai.core.server.workflow.executor;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.VariablePool;
import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregatorExecutorTest {
    // a, b -> agg : the aggregator coalesces two parallel branches that each produced files.
    private static final WorkflowGraph GRAPH = new WorkflowGraph(
        List.of(new WorkflowNode("a", "AGENT"), new WorkflowNode("b", "AGENT"), new WorkflowNode("agg", "AGGREGATOR")),
        List.of(new WorkflowEdge("e1", "a", "agg"), new WorkflowEdge("e2", "b", "agg")));

    private static NodeContext ctx(VariablePool pool) {
        return ctx(pool, Map.of());
    }

    private static NodeContext ctx(VariablePool pool, Map<String, Object> config) {
        return ctx(GRAPH, pool, config);
    }

    private static NodeContext ctx(WorkflowGraph graph, VariablePool pool, Map<String, Object> config) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.input = "{}";
        return new NodeContext(graph, run, new WorkflowNode("agg", "AGGREGATOR", List.of(), config), List.of(), pool);
    }

    private static ArtifactRef ref(String fileId, String fileName) {
        var artifact = new AgentRunArtifact();
        artifact.fileId = fileId;
        artifact.fileName = fileName;
        return ArtifactRef.of(artifact, "https://h/api/files/" + fileId + "/content");
    }

    @Test
    void unionsPredecessorArtifactsDedupedByFileIdInOrder() {
        var pool = new VariablePool(Map.of(), Map.of(
            "a", List.of(ref("f1", "a.pdf"), ref("shared", "dup.csv")),
            "b", List.of(ref("shared", "dup.csv"), ref("f2", "b.png"))), "{}");
        var outcome = new AggregatorExecutor().execute(ctx(pool));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(List.of("f1", "shared", "f2"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }

    @Test
    void noPredecessorArtifactsYieldsEmpty() {
        var outcome = new AggregatorExecutor().execute(ctx(new VariablePool(Map.of(), "{}")));
        assertTrue(assertInstanceOf(NodeOutcome.Normal.class, outcome).artifacts().isEmpty());
    }

    @Test
    void outputTemplateLiftsReferencedNonPredecessorArtifacts() {
        // an aggregator output template that references an ancestor's files (not a direct predecessor) lifts them,
        // so they propagate downstream instead of being dropped at the aggregator boundary.
        var pool = new VariablePool(Map.of(), Map.of(
            "a", List.of(ref("f1", "a.pdf")),
            "scratch", List.of(ref("tmp", "scratch.csv"))), "{}");
        var outcome = new AggregatorExecutor().execute(ctx(pool, Map.of("output", "see {{ nodes.scratch.artifacts }}")));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(List.of("f1", "tmp"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }

    @Test
    void outputTemplateLiftKeepsAggregatorAppendOrder() {
        var graph = new WorkflowGraph(
            List.of(new WorkflowNode("scratch", "AGENT"), new WorkflowNode("a", "AGENT"), new WorkflowNode("agg", "AGGREGATOR")),
            List.of(new WorkflowEdge("e0", "scratch", "a"), new WorkflowEdge("e1", "a", "agg")));
        var pool = new VariablePool(Map.of(), Map.of(
            "scratch", List.of(ref("tmp", "scratch.csv")),
            "a", List.of(ref("f1", "a.pdf"))), "{}");
        var outcome = new AggregatorExecutor().execute(ctx(graph, pool, Map.of("output", "see {{ nodes.scratch.artifacts }}")));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(List.of("f1", "tmp"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }
}
