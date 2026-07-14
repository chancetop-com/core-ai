package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeExecutorRegistryTest {
    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = "{\"q\": \"hi\"}";
        return run;
    }

    private static NodeContext ctx(WorkflowGraph graph, WorkflowRun run, WorkflowNode node) {
        return new NodeContext(graph, run, node, List.of(), new VariablePool(Map.of(), run.input));
    }

    @Test
    void unregisteredNodeTypeThrows() {
        var registry = new NodeExecutorRegistry(Map.of(NodeType.START, new StartExecutor()));
        var graph = new WorkflowGraph(List.of(new WorkflowNode("e", "END")), List.of());

        assertThrows(IllegalStateException.class,
            () -> registry.execute(ctx(graph, run(), graph.node("e"))));
    }

    @Test
    void unknownNodeTypeThrows() {
        var registry = new NodeExecutorRegistry(Map.of(NodeType.START, new StartExecutor()));
        var graph = new WorkflowGraph(List.of(new WorkflowNode("x", "BOGUS")), List.of());

        assertThrows(IllegalStateException.class,
            () -> registry.execute(ctx(graph, run(), graph.node("x"))));
    }

    @Test
    void realRegistryDrivesParsedStartToEndToCompleted() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """);
        NodeExecutor registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.END, new EndExecutor()));
        var journal = new InMemoryWorkflowJournal();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(registry, Runnable::run), () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "start"));
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
    }
}
