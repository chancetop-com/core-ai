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

class EndExecutorTest {
    // draft, report -> end : two predecessors with files; "scratch" is upstream of report but NOT adjacent to end.
    private static final WorkflowGraph GRAPH = new WorkflowGraph(
        List.of(new WorkflowNode("scratch", "AGENT"), new WorkflowNode("draft", "AGENT"),
            new WorkflowNode("report", "AGENT"), new WorkflowNode("end", "END")),
        List.of(new WorkflowEdge("e0", "scratch", "report"),
            new WorkflowEdge("e1", "draft", "end"), new WorkflowEdge("e2", "report", "end")));

    @Test
    void deliverablesDefaultToPredecessorUnionNotWholeGraph() {
        var pool = new VariablePool(Map.of("draft", "d", "report", "r"), Map.of(
            "scratch", List.of(ref("tmp", "scratch.csv")),
            "draft", List.of(ref("f1", "draft.md")),
            "report", List.of(ref("f2", "report.pdf"))), "{}");
        var outcome = new EndExecutor().execute(ctx(pool, Map.of()));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        // only the END's immediate predecessors deliver; the scratch node's file stays trace-level
        assertEquals(List.of("f1", "f2"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }

    @Test
    void explicitArtifactsSelectorsOverrideTheDefaultUnion() {
        var pool = new VariablePool(Map.of("draft", "d", "report", "r"), Map.of(
            "draft", List.of(ref("f1", "draft.md")),
            "report", List.of(ref("f2", "report.pdf"))), "{}");
        var outcome = new EndExecutor().execute(ctx(pool, Map.of("artifacts", List.of("nodes.report.artifacts"))));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(List.of("f2"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }

    @Test
    void explicitSelectorsAcceptTemplateBracesAndIgnoreNonArtifactEntries() {
        var pool = new VariablePool(Map.of("report", "r"), Map.of(
            "report", List.of(ref("f2", "report.pdf"))), "{}");
        var outcome = new EndExecutor().execute(ctx(pool,
            Map.of("artifacts", List.of("{{ nodes.report.artifacts }}", "nodes.report.output"))));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(List.of("f2"), normal.artifacts().stream().map(r -> r.fileId).toList());
    }

    @Test
    void noPredecessorArtifactsYieldsEmptyDeliverables() {
        var outcome = new EndExecutor().execute(ctx(new VariablePool(Map.of("draft", "d"), "{}"), Map.of()));
        assertTrue(assertInstanceOf(NodeOutcome.Normal.class, outcome).artifacts().isEmpty());
    }

    private static NodeContext ctx(VariablePool pool, Map<String, Object> config) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.input = "{}";
        return new NodeContext(GRAPH, run, new WorkflowNode("end", "END", List.of(), config), List.of(), pool);
    }

    private static ArtifactRef ref(String fileId, String fileName) {
        var artifact = new AgentRunArtifact();
        artifact.fileId = fileId;
        artifact.fileName = fileName;
        return ArtifactRef.of(artifact, "https://h/api/files/" + fileId + "/content");
    }
}
