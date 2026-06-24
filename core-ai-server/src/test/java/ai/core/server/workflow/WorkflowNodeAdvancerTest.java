package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import ai.core.server.workflow.executor.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowNodeAdvancerTest {
    @Test
    void workflowNodeSubmitsChildAndParksRunPaused() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"},
                       {"id": "call_sub", "type": "WORKFLOW",
                        "config": {"source_workflow_id": "wf-b", "version_id": "ver-b"}},
                       {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "call_sub"},
                       {"id": "e1", "source": "call_sub", "target": "end"}]}
            """);
        var gateway = new RecordingGateway("wfrun-child-1");
        NodeExecutor registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.WORKFLOW, new WorkflowExecutor(gateway, 5),
            NodeType.END, new EndExecutor()));
        var journal = new InMemoryWorkflowJournal();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, registry, Runnable::run, () -> false);

        assertEquals(RunStatus.PAUSED, status);   // parked on the sub-workflow, lease released by the runner
        assertEquals(NodeRunStatus.WAITING, journal.status("run-1", "call_sub"));
        assertEquals("wfrun-child-1", journal.childRunId("run-1", "call_sub"));
    }

    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = "u1";
        run.workflowId = "wf-a";
        run.input = "{}";
        return run;
    }

    private static final class RecordingGateway implements WorkflowRunGateway {
        private final String childRunId;

        RecordingGateway(String childRunId) {
            this.childRunId = childRunId;
        }

        @Override
        public String submitChildRun(WorkflowRun parent, WorkflowNode node, String input, int childDepth) {
            return childRunId;
        }

        @Override
        public void cancelSubtree(String childRunId) {
        }
    }
}
