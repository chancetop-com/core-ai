package ai.core.server.workflow;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.sandbox.StagedFile;
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
    private static WorkflowGraph emptyGraph() {
        return new WorkflowGraph(List.of(), List.of());
    }

    private static NodeContext ctx(WorkflowGraph graph, WorkflowRun run, WorkflowNode node) {
        return new NodeContext(graph, run, node, List.of(), new VariablePool(Map.of(), run.input));
    }

    private static WorkflowRun run() {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.workflowId = "wf-1";
        run.input = "{\"ticket\": \"login broken\"}";
        return run;
    }

    @Test
    void completedChildRunBecomesNormalWithChildLink() {
        var gateway = new FakeGateway();
        gateway.scriptComplete("{\"category\":\"auth\"}");
        var outcome = new AgentExecutor(gateway).execute(ctx(emptyGraph(), run(), new WorkflowNode("classify", "AGENT")));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals("{\"category\":\"auth\"}", normal.output());
        assertEquals("agentrun-1", normal.childRunId());
    }

    @Test
    void completedChildRunCarriesTraceMetadata() {
        var usage = new TokenUsage();
        usage.input = 12L;
        usage.output = 7L;
        var gateway = new FakeGateway();
        gateway.scriptComplete("{}", List.of(), "trace-123", RunStatus.COMPLETED, usage);
        var node = new WorkflowNode("summarize", "AGENT", List.of(), Map.of(
            "agent_id", "agent-123",
            "agent_name", "Support Summarizer"));

        var outcome = new AgentExecutor(gateway).execute(ctx(emptyGraph(), run(), node));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals("agent-123", normal.traceMetadata().agentId);
        assertEquals("Support Summarizer", normal.traceMetadata().agentName);
        assertEquals("gpt-4.1", normal.traceMetadata().model);
        assertEquals("trace-123", normal.traceMetadata().childTraceId);
        assertEquals("COMPLETED", normal.traceMetadata().childStatus);
        assertEquals(12L, normal.traceMetadata().tokenUsage.input);
        assertEquals(7L, normal.traceMetadata().tokenUsage.output);
    }

    @Test
    void llmNodeCarriesTraceMetadataToo() {
        var gateway = new FakeGateway();
        gateway.scriptComplete("{}", List.of(), "trace-llm", RunStatus.COMPLETED, null);
        var node = new WorkflowNode("extract", "LLM", List.of(), Map.of(
            "agent_id", "llm-123",
            "agent_name", "Invoice Extractor"));

        var outcome = new AgentExecutor(gateway).execute(ctx(emptyGraph(), run(), node));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals("llm-123", normal.traceMetadata().agentId);
        assertEquals("Invoice Extractor", normal.traceMetadata().agentName);
        assertEquals("gpt-4.1", normal.traceMetadata().model);
        assertEquals("trace-llm", normal.traceMetadata().childTraceId);
    }

    @Test
    void completedChildRunCarriesArtifactsToNormal() {
        var artifact = new AgentRunArtifact();
        artifact.fileId = "f1";
        artifact.fileName = "report.pdf";
        var gateway = new FakeGateway();
        gateway.scriptComplete("{}", List.of(ArtifactRef.of(artifact, "https://h/api/files/f1/content")));
        var outcome = new AgentExecutor(gateway).execute(ctx(emptyGraph(), run(), new WorkflowNode("a", "AGENT")));

        var normal = assertInstanceOf(NodeOutcome.Normal.class, outcome);
        assertEquals(1, normal.artifacts().size());
        assertEquals("report.pdf", normal.artifacts().get(0).fileName);
        assertEquals("https://h/api/files/f1/content", normal.artifacts().get(0).url);
    }

    @Test
    void failedChildRunBecomesFailWithChildLink() {
        var gateway = new FakeGateway();
        gateway.scriptFail("llm unavailable");
        var outcome = new AgentExecutor(gateway).execute(ctx(emptyGraph(), run(), new WorkflowNode("a", "AGENT")));

        var fail = assertInstanceOf(NodeOutcome.Fail.class, outcome);
        assertEquals("llm unavailable", fail.error());
        assertEquals("java.lang.IllegalStateException: llm unavailable", fail.errorStack());
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
        gateway.scriptComplete("{\"ok\": true}", List.of(), "trace-123", RunStatus.COMPLETED, null);
        NodeExecutor registry = new NodeExecutorRegistry(Map.of(
            NodeType.START, new StartExecutor(),
            NodeType.AGENT, new AgentExecutor(gateway),
            NodeType.END, new EndExecutor()));
        var journal = new InMemoryWorkflowJournal();

        RunStatus status = WorkflowAdvancer.drive(graph, run(), journal, new WorkflowAdvancer.ExecCtx(registry, Runnable::run), () -> false);

        assertEquals(RunStatus.COMPLETED, status);
        assertEquals(NodeRunStatus.COMPLETED, journal.status("run-1", "classify"));
        assertEquals("agentrun-1", journal.childRunId("run-1", "classify"));   // two-layer run stays linked
        assertEquals("trace-123", journal.childTraceId("run-1", "classify"));
    }

    static final class FakeGateway implements AgentRunGateway {
        private AgentRunResult scripted = AgentRunResult.completed("{}");

        void scriptComplete(String output) {
            scripted = AgentRunResult.completed(output);
        }

        void scriptComplete(String output, List<ArtifactRef> artifacts) {
            scripted = AgentRunResult.completed(output, artifacts);
        }

        void scriptComplete(String output, List<ArtifactRef> artifacts, String traceId, RunStatus status, TokenUsage tokenUsage) {
            scripted = AgentRunResult.completed(output, artifacts, traceId, status, tokenUsage);
        }

        void scriptFail(String error) {
            scripted = AgentRunResult.failed(error, "java.lang.IllegalStateException: " + error, null, null, null);
        }

        @Override
        public StartedAgentRun startChildRun(WorkflowRun run, WorkflowNode node, String input, List<StagedFile> stagedFiles) {
            return new StartedAgentRun("agentrun-1", "gpt-4.1", null);
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
