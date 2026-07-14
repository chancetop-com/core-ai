package ai.core.cli.trace;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects a per-turn span tree (root AGENT -> LLM -> TOOL) from agent lifecycle hooks and uploads it
 * at turn end. V1: single-agent sequential nesting. parentSpanId is populated so the server schema is V2-ready.
 *
 * @author Xander
 */
public class TraceCollectorLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceCollectorLifecycle.class);
    private static final String SERVICE_NAME = "core-ai-cli";
    private static final String SERVICE_VERSION = "1";
    private static final String ENVIRONMENT = "cli";
    private static final int MAX_FIELD_LEN = 100_000;

    // Telemetry must never break the agent turn: isolate every hook body. Catch Throwable because
    // JsonUtil.toJson throws Error on null and the lifecycle dispatch in Agent has no try/catch.
    private static void safe(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            LOGGER.warn("trace collection hook failed, skipping", t);
        }
    }

    private static String toolKey(FunctionCall call) {
        if (call == null) return "tool";
        if (call.id != null) return call.id;
        return call.function != null ? call.function.name : "tool";
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_FIELD_LEN ? value : value.substring(0, MAX_FIELD_LEN);
    }

    private static String hex32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String hex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private final TraceUploader uploader;
    private volatile TurnState turn;

    public TraceCollectorLifecycle(TraceUploader uploader) {
        this.uploader = uploader;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        safe(() -> {
            var state = new TurnState();
            state.traceId = hex32();
            state.rootSpanId = hex16();
            state.currentLlmSpanId = state.rootSpanId;
            state.startedAtMs = System.currentTimeMillis();
            state.input = query != null ? query.get() : null;
            this.turn = state;
        });
    }

    @Override
    public void beforeModel(CompletionRequest req, ExecutionContext ctx) {
        safe(() -> {
            var state = turn;
            if (state == null) return;
            state.llmStartMs = System.currentTimeMillis();
        });
    }

    @Override
    public void afterModel(CompletionRequest req, CompletionResponse resp, ExecutionContext ctx) {
        safe(() -> {
            var state = turn;
            if (state == null) return;
            var now = System.currentTimeMillis();
            var span = new CliTraceSpan();
            span.traceId = state.traceId;
            span.spanId = hex16();
            span.parentSpanId = state.rootSpanId;
            span.name = "llm";
            span.type = "LLM";
            span.model = req != null ? req.model : null;
            span.input = req != null && req.messages != null ? truncate(JsonUtil.toJson(req.messages)) : null;
            span.output = resp != null && resp.choices != null ? truncate(JsonUtil.toJson(resp.choices)) : null;
            applyUsage(span, resp != null ? resp.usage : null);
            span.status = "OK";
            var start = state.llmStartMs > 0 ? state.llmStartMs : now;
            span.startedAtEpochMs = start;
            span.completedAtEpochMs = now;
            span.durationMs = now - start;
            state.spans.add(span);
            state.currentLlmSpanId = span.spanId;
        });
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext ctx) {
        safe(() -> {
            var state = turn;
            if (state == null) return;
            state.toolStartMs.put(toolKey(functionCall), System.currentTimeMillis());
        });
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext ctx, ToolCallResult result) {
        safe(() -> {
            var state = turn;
            if (state == null) return;
            var now = System.currentTimeMillis();
            var start = state.toolStartMs.getOrDefault(toolKey(functionCall), now);
            var span = new CliTraceSpan();
            span.traceId = state.traceId;
            span.spanId = hex16();
            span.parentSpanId = state.currentLlmSpanId;
            span.name = functionCall != null && functionCall.function != null ? functionCall.function.name : "tool";
            span.type = "TOOL";
            span.input = functionCall != null && functionCall.function != null ? truncate(functionCall.function.arguments) : null;
            span.output = result != null ? truncate(result.getResult()) : null;
            span.status = result != null && result.isFailed() ? "ERROR" : "OK";
            span.startedAtEpochMs = start;
            span.completedAtEpochMs = now;
            span.durationMs = now - start;
            state.spans.add(span);
        });
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext ctx) {
        safe(() -> finish("OK", result != null ? result.get() : null));
    }

    @Override
    public void afterAgentFailed(String query, ExecutionContext ctx, Exception exception) {
        safe(() -> finish("ERROR", exception != null ? exception.getMessage() : "error"));
    }

    private void finish(String status, String output) {
        var state = turn;
        if (state == null) return;
        this.turn = null;
        var now = System.currentTimeMillis();
        var root = new CliTraceSpan();
        root.traceId = state.traceId;
        root.spanId = state.rootSpanId;
        root.parentSpanId = null;
        root.name = "cli turn";
        root.type = "AGENT";
        root.input = truncate(state.input);
        root.output = truncate(output);
        root.status = status;
        root.durationMs = now - state.startedAtMs;
        root.startedAtEpochMs = state.startedAtMs;
        root.completedAtEpochMs = now;
        var spans = new ArrayList<CliTraceSpan>();
        spans.add(root);
        spans.addAll(state.spans);

        var request = new CliTraceRequest();
        request.serviceName = SERVICE_NAME;
        request.serviceVersion = SERVICE_VERSION;
        request.environment = ENVIRONMENT;
        request.spans = spans;
        uploader.upload(request);
    }

    private void applyUsage(CliTraceSpan span, Usage usage) {
        if (usage == null) return;
        span.inputTokens = (long) usage.getPromptTokens();
        span.outputTokens = (long) usage.getCompletionTokens();
        var details = usage.getPromptTokensDetails();
        span.cachedTokens = details != null ? (long) details.cachedTokens : 0L;
    }

    // Mutable per-turn state. The REPL runs turns sequentially and all lifecycle callbacks fire on the
    // main agent thread, so this is effectively single-threaded; the concurrent collections and volatile
    // field are kept as cheap defensive guards. currentLlmSpanId points at the most recent LLM span so
    // tool spans nest under the call that triggered them.
    private static final class TurnState {
        String traceId;
        String rootSpanId;
        volatile String currentLlmSpanId;
        volatile long llmStartMs;
        long startedAtMs;
        String input;
        final java.util.Queue<CliTraceSpan> spans = new ConcurrentLinkedQueue<>();
        final Map<String, Long> toolStartMs = new ConcurrentHashMap<>();
    }
}
