package ai.core.server.workflow.executor;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.VariablePool;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeExecutorTest {
    @Test
    void completedStdoutBecomesTrimmedNormalOutput() {
        var normal = assertInstanceOf(NodeOutcome.Normal.class, CodeExecutor.toOutcome(ToolCallResult.completed("5\n")));
        assertEquals("5", normal.output());
    }

    @Test
    void failedResultBecomesDeterministicFail() {
        var fail = assertInstanceOf(NodeOutcome.Fail.class, CodeExecutor.toOutcome(ToolCallResult.failed("name error")));
        assertEquals("name error", fail.error());
        assertFalse(fail.retryable());
    }

    @Test
    void buildScriptPrependsResolvedInputs() {
        String script = CodeExecutor.buildScript("print(inputs['x'])", Map.of("x", "hi"));
        assertTrue(script.contains("inputs = json.loads"));
        assertTrue(script.contains("print(inputs['x'])"));
    }

    @Test
    void buildScriptWithoutInputsIsUnchanged() {
        assertEquals("print(1)", CodeExecutor.buildScript("print(1)", Map.of()));
    }

    @Test
    void missingCodeFailsBeforeTouchingSandbox() {
        var node = new WorkflowNode("c", "CODE");   // no config
        var outcome = new CodeExecutor(null).execute(ctx(node));
        var fail = assertInstanceOf(NodeOutcome.Fail.class, outcome);
        assertTrue(fail.error().contains("no code"));
    }

    @Test
    void absentSandboxServiceFailsClearly() {
        var node = new WorkflowNode("c", "CODE", List.of(), Map.of("code", "print(1)"));
        var outcome = new CodeExecutor(null).execute(ctx(node));
        var fail = assertInstanceOf(NodeOutcome.Fail.class, outcome);
        assertTrue(fail.error().contains("sandbox"));
    }

    private static NodeContext ctx(WorkflowNode node) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = "user-1";
        run.input = "{}";
        return new NodeContext(new WorkflowGraph(List.of(node), List.of()), run, node, List.of(), new VariablePool(Map.of(), "{}"));
    }
}
