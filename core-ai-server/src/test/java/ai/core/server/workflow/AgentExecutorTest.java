package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.server.workflow.executor.AgentExecutor;
import ai.core.server.workflow.executor.EndExecutor;
import ai.core.server.workflow.executor.StartExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentExecutorTest {
    @Test
    void completedChildRunBecomesNormalWithChildLink() {
        var gateway = new FakeGateway();
        gateway.scriptComplete("{\"category\":\"auth\"}");
        var outcome = new AgentExecutor(gateway).execute(emptyGraph(), run(), new WorkflowNode("classify", "AGENT"), List.of());

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals("{\"category\":\"auth\"}", normal.output());
        assertEquals("agentrun-1", normal.childRunId());
    }

    @Test
    void failedChildRunBecomesFailWithChildLink() {
        var gateway = new FakeGateway();
        gateway.scriptFail("llm unavailable");
        var outcome = new AgentExecutor(gateway).execute(emptyGraph(), run(), new WorkflowNode("a", "AGENT"), List.of());

        var fail = assertInstanceOf(NodeOutcome.Fail.class, outcome);
        assertEquals("llm unavailable", fail.error());
        assertEquals("agentrun-1", fail.childRunId());
    }

    @Test
    void registryDrivesStartAgentEndRecordingChildRun() {
        WorkflowGraph graph = WorkflowGraphParser.parse("""
            {"nodes": [{"id": "start", "type": "START"},
                       {"id": "classify", "type": "AGENT", "config": {"agent_id": "agent-123"}},
                       {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "classify"},
                       {"id": "e1", "source": "classify", "target": "end"}]}
            """);
        var gateway = new FakeGateway();
        gateway.scriptComplete("{\"ok\": true}");
        NodeExecutor registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.AGENT, new AgentExecutor(gateway),
            NodeType.END, new EndExecutor()));
        var journal = new InMemoryWorkflowJournal();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, registry, Runnable::run, () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "classify"));
        assertEquals("agentrun-1", journal.childRunId("run-1", "classify"));   // two-layer run stays linked
    }

    private static WorkflowGraph emptyGraph() {
        return new WorkflowGraph(List.of(), List.of());
    }

    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = "{\"ticket\": \"login broken\"}";
        return run;
    }

    static final class FakeGateway implements AgentRunGateway {
        private AgentRunResult scripted = AgentRunResult.completed("{}");

        void scriptComplete(String output) {
            scripted = AgentRunResult.completed(output);
        }

        void scriptFail(String error) {
            scripted = AgentRunResult.failed(error);
        }

        @Override
        public String startChildRun(WorkflowRun run, WorkflowNode node, String input) {
            return "agentrun-1";
        }

        @Override
        public AgentRunResult awaitResult(String childRunId) {
            return scripted;
        }

        @Override
        public void cancel(String childRunId) {
        }
    }
}
