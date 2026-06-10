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
    void sentinelResultBecomesTheOutputAndStdoutBeforeItIsDebugOnly() {
        String stdout = "debug line\n\n" + CodeExecutor.RESULT_SENTINEL + "\n{\"total\": 3}\n";
        var normal = assertInstanceOf(NodeOutcome.Normal.class, CodeExecutor.toOutcome(ToolCallResult.completed(stdout)));
        assertEquals("{\"total\": 3}", normal.output());
    }

    @Test
    void buildScriptPrependsResolvedInputs() {
        String script = CodeExecutor.buildScript("print(inputs['x'])", Map.of("x", "hi"));
        assertTrue(script.contains("inputs = json.loads"));
        assertTrue(script.contains("print(inputs['x'])"));
    }

    @Test
    void buildScriptAppendsResultEpilogueAfterUserCode() {
        String script = CodeExecutor.buildScript("result = {'a': 1}", Map.of());
        int code = script.indexOf("result = {'a': 1}");
        int epilogue = script.indexOf("if 'result' in globals():");
        assertTrue(code >= 0 && epilogue > code);
        assertTrue(script.contains(CodeExecutor.RESULT_SENTINEL));
    }

    @Test
    void buildScriptAlwaysDefinesInputs() {
        String script = CodeExecutor.buildScript("print(1)", Map.of());
        assertTrue(script.contains("inputs = json.loads"));
        assertTrue(script.contains("print(1)"));
    }

    @Test
    void coerceParsesWholeOutputJsonObjectIntoAMap() {
        assertEquals(Map.of("k", "v"), CodeExecutor.coerce("{\"k\": \"v\"}"));
    }

    @Test
    void coerceLeavesPlainStringsAndNonStringsUnchanged() {
        assertEquals("hello", CodeExecutor.coerce("hello"));
        assertEquals(42, CodeExecutor.coerce(42));
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
