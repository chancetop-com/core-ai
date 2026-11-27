package ai.core.cli.trace;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        req.clientType = "desktop";
        req.spans = List.of(s);

        var map = HttpTraceUploader.toMap(req);
        assertEquals("core-ai-cli", map.get("serviceName"));
        assertEquals("desktop", map.get("clientType"));
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
        assertTrue(json.contains("\"clientType\":\"desktop\""));
    }

    @Test
    void uploadCarriesDeclaredClientType() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "desktop");
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        assertNotNull(captured);
        assertEquals("desktop", captured.clientType, "desktop sessions must not be attributed to the CLI");
    }

    @Test
    void buildsNestedSpanTreeForOneTurn() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");

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
        assertEquals("cli", captured.clientType);
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
    void llmSpanRecordsTheModelTheUpstreamReported() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);

        var request = CompletionRequest.of(new ArrayList<>(), null, 0.8, "deepseek-flash", "agent");
        var response = CompletionResponse.of(List.of(), new Usage(10, 5, 15));
        // the server-side gateway failed over and the upstream answered with the fallback model
        response.model = "gpt-6-luna";
        lifecycle.afterModel(request, response, null);
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var llm = captured.spans.stream().filter(s -> "LLM".equals(s.type)).findFirst().orElseThrow();
        assertEquals("deepseek-flash", llm.model);
        assertEquals("gpt-6-luna", llm.resolvedModel);

        var map = HttpTraceUploader.toMap(captured);
        var spanMap = (java.util.Map<?, ?>) ((List<?>) map.get("spans")).get(1);
        assertEquals("gpt-6-luna", spanMap.get("resolvedModel"));
    }

    @Test
    void marksRootErrorOnFailure() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterAgentFailed("hi", null, new RuntimeException("boom"));

        assertNotNull(captured);
        var root = captured.spans.stream().filter(s -> "AGENT".equals(s.type)).findFirst().orElseThrow();
        assertEquals("ERROR", root.status);
    }

    @Test
    void llmSpanSurvivesNullMessagesWithoutBreakingTurn() {
        // req.messages == null must NOT throw out of the hook (JsonUtil.toJson(null) throws Error).
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
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
    void recoverableToolFailureMarksToolSpanWarning() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterModel(CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a"),
                CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        var call = FunctionCall.of("c1", "function", "grep_file", "{}");
        lifecycle.beforeTool(call, null);
        lifecycle.afterTool(call, null, ToolCallResult.failed("Error: path does not exist: nope"));
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var tool = captured.spans.stream().filter(s -> "TOOL".equals(s.type)).findFirst().orElseThrow();
        assertEquals("WARNING", tool.status, "a missing path is a model-level miss, not a failed turn");
    }

    @Test
    void hardToolFailureMarksToolSpanError() {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterModel(CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a"),
                CompletionResponse.of(List.of(), new Usage(1, 1, 2)), null);
        var call = FunctionCall.of("c1", "function", "bad_tool", "{}");
        lifecycle.beforeTool(call, null);
        lifecycle.afterTool(call, null, ToolCallResult.hardFailed("SANDBOX_UNAVAILABLE: docker is down"));
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        var tool = captured.spans.stream().filter(s -> "TOOL".equals(s.type)).findFirst().orElseThrow();
        assertEquals("ERROR", tool.status);
    }

    @Test
    void llmSpanCapturesDurationFromBeforeModel() throws InterruptedException {
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
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
        var lifecycle = new TraceCollectorLifecycle(req -> this.captured = req, "cli");
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

    @Test
    void beforeAgentRunEmitsStartWithTruncatedInputAndConsistentMetadata() {
        var recorder = new RecordingUploader();
        var lifecycle = new TraceCollectorLifecycle(recorder, "desktop");
        var longQuery = "q".repeat(120_000);

        lifecycle.beforeAgentRun(new AtomicReference<>(longQuery), null);

        assertEquals(1, recorder.starts.size());
        assertTrue(recorder.spans.isEmpty());
        assertTrue(recorder.uploads.isEmpty());

        var start = recorder.starts.get(0);
        assertNotNull(start.traceId);
        assertEquals(32, start.traceId.length());
        assertEquals("cli turn", start.name);
        assertEquals(100_000, start.input.length());
        assertNotNull(start.startedAtEpochMs);
        assertEquals("core-ai-cli", start.serviceName);
        assertEquals("1", start.serviceVersion);
        assertEquals("cli", start.environment);
        assertEquals("desktop", start.clientType);

        lifecycle.afterAgentRun(longQuery, new AtomicReference<>("ok"), null);

        assertEquals(1, recorder.uploads.size());
        var finalReq = recorder.uploads.get(0);
        assertEquals(start.serviceName, finalReq.serviceName);
        assertEquals(start.serviceVersion, finalReq.serviceVersion);
        assertEquals(start.environment, finalReq.environment);
        assertEquals(start.clientType, finalReq.clientType);
        assertEquals(1, finalReq.spans.size());
        var root = finalReq.spans.get(0);
        assertEquals(start.traceId, root.traceId);
        assertEquals(start.startedAtEpochMs, root.startedAtEpochMs);
        assertEquals(start.input, root.input);
    }

    @Test
    void afterModelAndAfterToolEmitCompletedSpansAndFinalIncludesRootPlusAll() {
        var recorder = new RecordingUploader();
        var lifecycle = new TraceCollectorLifecycle(recorder, "cli");

        lifecycle.beforeAgentRun(new AtomicReference<>("hello"), null);
        var request = CompletionRequest.of(new ArrayList<>(), null, 0.8, "gpt-4o", "agent");
        var usage = new Usage(12, 7, 19);
        var response = CompletionResponse.of(List.of(), usage);
        lifecycle.beforeModel(request, null);
        lifecycle.afterModel(request, response, null);

        var call1 = FunctionCall.of("call-1", "function", "read_file", "{\"path\":\"a\"}");
        lifecycle.beforeTool(call1, null);
        lifecycle.afterTool(call1, null, ToolCallResult.completed("contents"));

        var call2 = FunctionCall.of("call-2", "function", "grep_file", "{\"q\":\"x\"}");
        lifecycle.beforeTool(call2, null);
        lifecycle.afterTool(call2, null, ToolCallResult.failed("not found"));

        lifecycle.afterAgentRun("hello", new AtomicReference<>("done"), null);

        assertEquals(List.of("start", "span:LLM", "span:TOOL", "span:TOOL", "upload"), recorder.events);
        assertEquals(1, recorder.starts.size());
        assertEquals(3, recorder.spans.size());
        assertEquals(1, recorder.uploads.size());

        var finalSpans = recorder.uploads.get(0).spans;
        assertEquals(4, finalSpans.size(), "final batch must carry root + all emitted spans");
        var root = finalSpans.get(0);
        var liveLlm = recorder.spans.get(0);
        var liveTool1 = recorder.spans.get(1);
        var liveTool2 = recorder.spans.get(2);

        assertSame(liveLlm, finalSpans.get(1));
        assertSame(liveTool1, finalSpans.get(2));
        assertSame(liveTool2, finalSpans.get(3));

        assertEquals(root.spanId, liveLlm.parentSpanId);
        assertEquals("OK", liveLlm.status);
        assertEquals(12L, liveLlm.inputTokens);
        assertEquals(7L, liveLlm.outputTokens);
        assertEquals(liveLlm.spanId, liveTool1.parentSpanId);
        assertEquals("OK", liveTool1.status);
        assertEquals(liveLlm.spanId, liveTool2.parentSpanId);
        assertEquals("WARNING", liveTool2.status);
    }

    @Test
    void uploaderExceptionsNeverBreakTurnAcrossAllHooks() {
        var flaky = new ThrowingUploader();
        var lifecycle = new TraceCollectorLifecycle(flaky, "cli");

        lifecycle.beforeAgentRun(new AtomicReference<>("hi"), null);
        lifecycle.afterModel(CompletionRequest.of(new ArrayList<>(), null, 0.8, "m", "a"),
                CompletionResponse.of(List.of(), new Usage(3, 4, 7)), null);
        var call = FunctionCall.of("c1", "function", "read_file", "{}");
        lifecycle.beforeTool(call, null);
        lifecycle.afterTool(call, null, ToolCallResult.completed("ok"));
        lifecycle.afterAgentRun("hi", new AtomicReference<>("done"), null);

        assertEquals(1, flaky.startCalls.get());
        assertEquals(2, flaky.spanCalls.get());
        assertEquals(1, flaky.uploadCalls.get());
        assertNotNull(flaky.capturedFinal.get(), "turn state must survive start/span uploader failures");
        assertEquals(3, flaky.capturedFinal.get().spans.size(), "root + llm + tool still collected");
        var root = flaky.capturedFinal.get().spans.get(0);
        var llm = flaky.capturedFinal.get().spans.get(1);
        var tool = flaky.capturedFinal.get().spans.get(2);
        assertEquals(root.spanId, llm.parentSpanId);
        assertEquals(llm.spanId, tool.parentSpanId);
    }

    private static final class ThrowingUploader implements TraceUploader {
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger spanCalls = new AtomicInteger();
        final AtomicInteger uploadCalls = new AtomicInteger();
        final AtomicReference<CliTraceRequest> capturedFinal = new AtomicReference<>();

        @Override
        public void start(CliTraceStart start) {
            startCalls.incrementAndGet();
            throw new IllegalStateException("start boom");
        }

        @Override
        public void span(CliTraceSpan span) {
            spanCalls.incrementAndGet();
            throw new IllegalStateException("span boom");
        }

        @Override
        public void upload(CliTraceRequest request) {
            uploadCalls.incrementAndGet();
            capturedFinal.set(request);
            throw new IllegalStateException("upload boom");
        }
    }

    private static final class RecordingUploader implements TraceUploader {
        final List<CliTraceStart> starts = new ArrayList<>();
        final List<CliTraceSpan> spans = new ArrayList<>();
        final List<CliTraceRequest> uploads = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void start(CliTraceStart start) {
            starts.add(start);
            events.add("start");
        }

        @Override
        public void span(CliTraceSpan span) {
            spans.add(span);
            events.add("span:" + span.type);
        }

        @Override
        public void upload(CliTraceRequest request) {
            uploads.add(request);
            events.add("upload");
        }
    }

}
