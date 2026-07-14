package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.workflow.engine.EdgeVerdict;
import ai.core.server.workflow.engine.Frontier;
import ai.core.server.workflow.engine.NodeFactStatus;
import ai.core.server.workflow.engine.Planner;
import ai.core.server.workflow.engine.RunState;
import ai.core.server.workflow.engine.WorkflowEdge;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStateAssemblerTest {
    private static WorkflowNodeRun nodeRun(String nodeId, NodeRunStatus status, List<String> chosenEdgeIds) {
        var nodeRun = new WorkflowNodeRun();
        nodeRun.nodeId = nodeId;
        nodeRun.status = status;
        nodeRun.chosenEdgeIds = chosenEdgeIds;
        nodeRun.scopePathKey = "";
        return nodeRun;
    }

    @Test
    void completedNormalNodeRunBecomesNormalFact() {
        RunState state = RunStateAssembler.toRunState(List.of(nodeRun("a", NodeRunStatus.COMPLETED, null)), "");

        assertEquals(NodeFactStatus.COMPLETED, state.factOf("a").status());
        assertTrue(state.factOf("a").chosenEdgeIds().isEmpty());
    }

    @Test
    void completedWithChosenEdgesBecomesBranchFact() {
        var fact = RunStateAssembler.toFact(nodeRun("if", NodeRunStatus.COMPLETED, List.of("e1")));

        assertEquals(NodeFactStatus.COMPLETED, fact.status());
        assertEquals(Set.of("e1"), fact.chosenEdgeIds());
    }

    @Test
    void failedRetryableProjectsToEngineFailed() {
        assertEquals(NodeFactStatus.FAILED, RunStateAssembler.toFact(nodeRun("a", NodeRunStatus.FAILED_RETRYABLE, null)).status());
    }

    @Test
    void waitingProjectsToRunningSoOutEdgesStayPending() {
        assertEquals(NodeFactStatus.RUNNING, RunStateAssembler.toFact(nodeRun("a", NodeRunStatus.WAITING, null)).status());
    }

    @Test
    void onlyFactsAtRequestedScopeAreIncluded() {
        WorkflowNodeRun root = nodeRun("a", NodeRunStatus.COMPLETED, null);   // scopePathKey ""
        WorkflowNodeRun inner = nodeRun("a", NodeRunStatus.COMPLETED, null);
        inner.scopePathKey = "ITERATION:loop:0";

        RunState state = RunStateAssembler.toRunState(List.of(root, inner), "");

        assertEquals(1, state.facts().size());
    }

    @Test
    void projectedStateDrivesPlannerBranchSelection() {
        // persisted node-runs -> RunState -> Planner produces the expected frontier (end-to-end schema check)
        WorkflowGraph graph = new WorkflowGraph(
            List.of(new WorkflowNode("if", "IF_ELSE"), new WorkflowNode("a", "AGENT"), new WorkflowNode("b", "AGENT")),
            List.of(new WorkflowEdge("e1", "if", "a"), new WorkflowEdge("e2", "if", "b")));

        RunState state = RunStateAssembler.toRunState(List.of(nodeRun("if", NodeRunStatus.COMPLETED, List.of("e1"))), "");
        Frontier f = Planner.plan(graph, state);

        assertEquals(EdgeVerdict.ACTIVE, f.edgeVerdicts().get("e1"));
        assertEquals(EdgeVerdict.SKIPPED, f.edgeVerdicts().get("e2"));
        assertEquals(Set.of("a"), f.readyNodeIds());
        assertEquals(Set.of("b"), f.skipNodeIds());
    }
}
