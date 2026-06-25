package ai.core.server.workflow;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.WorkflowExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutorTest {
    private static final int MAX_DEPTH = 5;

    @Test
    void submitsChildAndParksAsSuspended() {
        var gateway = new FakeWorkflowGateway("wfrun-child-1");
        var node = new WorkflowNode("call_sub", "WORKFLOW", List.of(), Map.of(
            "source_workflow_id", "wf-b", "version_id", "ver-b",
            "input_mappings", Map.of("city", "{{ sys.input }}")));
        var outcome = new WorkflowExecutor(gateway, MAX_DEPTH).execute(ctx(node, depthRun(0)));

        var suspended = assertInstanceOf(NodeOutcome.Suspended.class, outcome);
        assertEquals("wfrun-child-1", suspended.childRunId());
        assertEquals(1, gateway.submittedDepth);   // child depth = parent 0 + 1
        assertEquals("{\"city\":\"shanghai\"}", gateway.submittedInput);
    }

    @Test
    void rejectsWhenDepthCapExceeded() {
        var gateway = new FakeWorkflowGateway("never");
        var node = new WorkflowNode("call_sub", "WORKFLOW", List.of(), Map.of(
            "source_workflow_id", "wf-b", "version_id", "ver-b"));
        var outcome = new WorkflowExecutor(gateway, MAX_DEPTH).execute(ctx(node, depthRun(MAX_DEPTH)));

        var fail = assertInstanceOf(NodeOutcome.Fail.class, outcome);
        assertTrue(fail.error().contains("nesting too deep"));
        assertEquals(0, gateway.submitCount);   // no child started past the cap
    }

    private static WorkflowRun depthRun(int depth) {
        var run = new WorkflowRun();
        run.id = "run-parent";
        run.userId = "u1";
        run.workflowId = "wf-a";
        run.input = "shanghai";
        run.depth = depth;
        return run;
    }

    private static NodeContext ctx(WorkflowNode node, WorkflowRun run) {
        WorkflowGraph graph = WorkflowGraphParser.parse(
            "{\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],"
            + "\"edges\":[{\"id\":\"e0\",\"source\":\"start\",\"target\":\"end\"}]}");
        var pool = VariablePool.fromNodeRuns(List.of(), "", run.input);
        return new NodeContext(graph, run, node, List.of(), pool);
    }

    // minimal in-test fake; records what WorkflowExecutor asked for
    private static final class FakeWorkflowGateway implements WorkflowRunGateway {
        private final String childRunId;
        int submitCount;
        int submittedDepth = -1;
        String submittedInput;

        FakeWorkflowGateway(String childRunId) {
            this.childRunId = childRunId;
        }

        @Override
        public String submitChildRun(WorkflowRun parent, WorkflowNode node, String input, int childDepth) {
            submitCount++;
            submittedDepth = childDepth;
            submittedInput = input;
            return childRunId;
        }

        @Override
        public void cancelSubtree(String childRunId) {
        }
    }
}
