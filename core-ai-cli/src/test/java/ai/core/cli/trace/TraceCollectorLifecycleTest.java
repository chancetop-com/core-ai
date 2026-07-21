package ai.core.cli.trace;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCollectorLifecycleTest {

    private CliTraceRequest captured;

    @Test
    void uploaderSerializesViaReflectionFreeMap() {
        // Guards the wire contract: HttpTraceUploader.toMap must emit the exact server field names,
        // and the result must serialize without per-class reflection (native-image safety).
        var s = new CliTraceSpan();
        s.traceId = "t1";
        s.spanId = "s1";
        s.parentSpanId = null;
        s.type = "LLM";
        s.inputTokens = 10L;
        s.startedAtEpochMs = 5L;
        var req = new CliTraceRequest();
        req.serviceName = "core-ai-cli";
        req.spans = List.of(s);

        var map = HttpTraceUploader.toMap(req);
        assertEquals("core-ai-cli", map.get("serviceName"));
        var spans = (List<?>) map.get("spans");
        assertEquals(1, spans.size());
        var sm = (java.util.Map<?, ?>) spans.get(0);
        assertEquals("t1", sm.get("traceId"));
        assertEquals("LLM", sm.get("type"));
        assertEquals(10L, sm.get("inputTokens"));
        assertEquals(5L, sm.get("startedAtEpochMs"));

        var json = ai.core.utils.JsonUtil.toJson(map);
        assertTrue(json.contains("\"traceId\":\"t1\""));
        assertTrue(json.contains("\"inputTokens\":10"));
    }

    @Test
    void buildsNestedSpanTreeForOneTurn() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);

        lifecycle.beforeAgentRun(new AtomicReference<>("hello"), null);

        var request = CompletionRequest.of(new ArrayList<>(), null, 0.8, "gpt-4o", "agent");
        var usage = new Usage(10, 5, 15);
        var response = CompletionResponse.of(List.of(), usage);
        lifecycle.afterModel(request, response, null);

        var call = FunctionCall.of("call-1", "function", "read_file", "{\"path\":\"x\"}");
        lifecycle.beforeTool(call, null);
        lifecycle.afterTool(call, null, ToolCallResult.completed("file contents"));

        lifecycle.afterAgentRun("hello", new AtomicReference<>("done"), null);

        assertNotNull(captured, "a batch should be uploaded at turn end");
        var spans = captured.spans;
        assertEquals(3, spans.size(), "root + llm + tool");

        var root = spans.stream().filter(s -> "AGENT".equals(s.type)).findFirst().orElseThrow();
        var llm = spans.stream().filter(s -> "LLM".equals(s.type)).findFirst().orElseThrow();
        var tool = spans.stream().filter(s -> "TOOL".equals(s.type)).findFirst().orElseThrow();

        assertNull(root.parentSpanId, "root has no parent");
        assertEquals(root.spanId, llm.parentSpanId, "llm nests under root");
        assertEquals(llm.spanId, tool.parentSpanId, "tool nests under its triggering llm call");

        assertEquals("done", root.output);
        assertEquals("OK", root.status);
        assertEquals("gpt-4o", llm.model);
        assertEquals(10L, llm.inputTokens);
        assertEquals(5L, llm.outputTokens);
        assertEquals("read_file", tool.name);
        assertTrue(spans.stream().allMatch(s -> s.traceId.equals(root.traceId)), "all spans share one traceId");
    }

    @Test
    void marksRootErrorOnFailure() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterAgentFailed("hi", null, new RuntimeException("boom"));

        assertNotNull(captured);
        var root = captured.spans.stream().filter(s -> "AGENT".equals(s.type)).findFirst().orElseThrow();
        assertEquals("ERROR", root.status);
    }

    @Test
    void llmSpanSurvivesNullMessagesWithoutBreakingTurn() {
        // req.messages == null must NOT throw out of the hook (JsonUtil.toJson(null) throws Error).
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        var request = CompletionRequest.of(null, null, 0.8, "gpt-4o", "agent");
        lifecycle.afterModel(request, CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        assertNotNull(captured);
        var llm = captured.spans.stream().filter(s -> "LLM".equals(s.type)).findFirst().orElseThrow();
        assertNull(llm.input, "null messages -> null input, span still recorded");
        assertEquals("gpt-4o", llm.model);
    }

    @Test
    void failingToolMarksToolSpanError() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterModel(CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a"),
                CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        var call = FunctionCall.of("c1", "function", "bad_tool", "{}");
        lifecycle.beforeTool(call, null);
        lifecycle.afterTool(call, null, ToolCallResult.failed("nope"));
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var tool = captured.spans.stream().filter(s -> "TOOL".equals(s.type)).findFirst().orElseThrow();
        assertEquals("ERROR", tool.status);
    }

    @Test
    void llmSpanCapturesDurationFromBeforeModel() throws InterruptedException {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        var request = CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a");
        lifecycle.beforeModel(request, null);
        Thread.sleep(2);
        lifecycle.afterModel(request, CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var llm = captured.spans.stream().filter(s -> "LLM".equals(s.type)).findFirst().orElseThrow();
        assertNotNull(llm.durationMs);
        assertTrue(llm.durationMs >= 0);
        assertTrue(llm.completedAtEpochMs >= llm.startedAtEpochMs);
        assertTrue(llm.startedAtEpochMs <= llm.completedAtEpochMs);
    }

    @Test
    void multipleToolsNestUnderSameLlmSpan() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req);
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterModel(CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a"),
                CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        var c1 = FunctionCall.of("c1", "function", "t1", "{}");
        var c2 = FunctionCall.of("c2", "function", "t2", "{}");
        lifecycle.beforeTool(c1, null);
        lifecycle.afterTool(c1, null, ToolCallResult.completed("a"));
        lifecycle.beforeTool(c2, null);
        lifecycle.afterTool(c2, null, ToolCallResult.completed("b"));
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var llm = captured.spans.stream().filter(s -> "LLM".equals(s.type)).findFirst().orElseThrow();
        var tools = captured.spans.stream().filter(s -> "TOOL".equals(s.type)).toList();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().allMatch(t -> llm.spanId.equals(t.parentSpanId)),
                "both tools nest under the same triggering llm span");
    }

}
