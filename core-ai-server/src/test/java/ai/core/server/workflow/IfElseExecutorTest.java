package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.AgentExecutor;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.IfElseExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IfElseExecutorTest {
    @Test
    void matchingCasePicksItsEdge() {
        var node = ifElse(
            List.of(caseOf("e_a", cond("nodes.start.output.kind", "eq", "a"))),
            "e_else");
        var pool = new VariablePool(Map.of("start", "{\"kind\": \"a\"}"), "{}");

        var branch = assertInstanceOf(NodeOutcome.Branch.class, new IfElseExecutor().execute(ctx(node, pool)));
        assertEquals(List.of("e_a"), branch.chosenEdgeIds());
    }

    @Test
    void noMatchFallsBackToElse() {
        var node = ifElse(
            List.of(caseOf("e_a", cond("nodes.start.output.kind", "eq", "a"))),
            "e_else");
        var pool = new VariablePool(Map.of("start", "{\"kind\": \"b\"}"), "{}");

        var branch = assertInstanceOf(NodeOutcome.Branch.class, new IfElseExecutor().execute(ctx(node, pool)));
        assertEquals(List.of("e_else"), branch.chosenEdgeIds());
    }

    @Test
    void numericGreaterThanOnRunInput() {
        var node = ifElse(List.of(caseOf("e_high", cond("input.score", "gt", "80"))), "e_low");
        assertEquals(List.of("e_high"), branchOf(node, new VariablePool(Map.of(), "{\"score\": 95}")));
        assertEquals(List.of("e_low"), branchOf(node, new VariablePool(Map.of(), "{\"score\": 50}")));
    }

    @Test
    void orLogicMatchesIfAnyConditionHolds() {
        var node = ifElse(
            List.of(caseOf("e_a", "or", List.of(cond("input.a", "eq", "1"), cond("input.b", "eq", "2")))),
            "e_else");
        assertEquals(List.of("e_a"), branchOf(node, new VariablePool(Map.of(), "{\"a\": \"x\", \"b\": \"2\"}")));
        assertEquals(List.of("e_else"), branchOf(node, new VariablePool(Map.of(), "{\"a\": \"x\", \"b\": \"y\"}")));
    }

    @Test
    void registryDrivesOnlyTheChosenBranch() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"},
                       {"id": "route", "type": "IF_ELSE",
                        "config": {"cases": [{"edge_id": "e_a",
                                              "conditions": [{"selector": "nodes.start.output.kind", "operator": "eq", "value": "a"}]}],
                                   "else_edge_id": "e_b"}},
                       {"id": "a", "type": "AGENT", "config": {"agent_id": "agent-a"}},
                       {"id": "b", "type": "AGENT", "config": {"agent_id": "agent-b"}},
                       {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "route"},
                       {"id": "e_a", "source": "route", "target": "a"},
                       {"id": "e_b", "source": "route", "target": "b"},
                       {"id": "e1", "source": "a", "target": "end"},
                       {"id": "e2", "source": "b", "target": "end"}]}
            """);
        var gateway = new RecordingGateway();
        NodeExecutor registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.IF_ELSE, new IfElseExecutor(),
            NodeType.AGENT, new AgentExecutor(gateway),
            NodeType.END, new EndExecutor()));
        var journal = new InMemoryWorkflowJournal();

        RunStatus status = WorkflowAdvancer.drive(graph, runWithKind("a"), journal, registry, Runnable::run, () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertTrue(gateway.started.contains("a"));
        assertFalse(gateway.started.contains("b"));   // the false branch was skipped, not executed
        assertEquals(NodeRunStatus.SKIPPED, journal.status("run-1", "b"));
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "end"));
    }

    // --- helpers ---

    private static List<String> branchOf(WorkflowNode node, VariablePool pool) {
        var branch = assertInstanceOf(NodeOutcome.Branch.class, new IfElseExecutor().execute(ctx(node, pool)));
        return branch.chosenEdgeIds();
    }

    private static NodeContext ctx(WorkflowNode node, VariablePool pool) {
        return new NodeContext(new WorkflowGraph(List.of(node), List.of()), runWithKind("x"), node, List.of(), pool);
    }

    private static WorkflowNode ifElse(List<Map<String, Object>> cases, String elseEdge) {
        return new WorkflowNode("route", "IF_ELSE", List.of(), Map.of("cases", cases, "else_edge_id", elseEdge));
    }

    private static Map<String, Object> caseOf(String edgeId, Map<String, Object> condition) {
        return Map.of("edge_id", edgeId, "conditions", List.of(condition));
    }

    private static Map<String, Object> caseOf(String edgeId, String logic, List<Map<String, Object>> conditions) {
        return Map.of("edge_id", edgeId, "logic", logic, "conditions", conditions);
    }

    private static Map<String, Object> cond(String selector, String operator, String value) {
        return Map.of("selector", selector, "operator", operator, "value", value);
    }

    private static WorkflowRun runWithKind(String kind) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = "{\"kind\": \"" + kind + "\"}";
        return run;
    }

    static final class RecordingGateway implements AgentRunGateway {
        final List<String> started = new CopyOnWriteArrayList<>();

        @Override
        public String startChildRun(WorkflowRun run, WorkflowNode node, String input, List<SandboxService.StagedFile> stagedFiles) {
            started.add(node.id());
            return "agentrun-" + node.id();
        }

        @Override
        public AgentRunResult awaitResult(String childRunId) {
            return AgentRunResult.completed("{}");
        }

        @Override
        public void cancel(String childRunId) {
        }
    }
}
