package ai.core.server.replay.service;

import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.streaming.DefaultStreamingCallback;
import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySample;
import ai.core.server.replay.domain.ReplaySampleStatus;
import ai.core.server.trace.service.ModelPricingService;
import ai.core.telemetry.TelemetryConfig;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.util.Strings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes replay run samples against the normal provider stack (preprocess,
 * gateway routing, modality enforcement — same behavior as real runs).
 * <p>
 * Each sample gets its own root span with client.type=replay, so replay calls
 * land in the regular trace/cost pipeline with source=replay. Sample progress is
 * written incrementally so the frontend poll sees per-sample results as they
 * arrive. Cancellation is best-effort: close the active upstream connection and
 * mark pending samples CANCELLED.
 *
 * @author stephen
 */
public class ReplayExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayExecutor.class);
    private static final int POOL_SIZE = 4;
    private static final int SAMPLE_TIMEOUT_SECONDS = 300;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final AttributeKey<String> CLIENT_TYPE = AttributeKey.stringKey("client.type");
    private static final AttributeKey<String> CORE_AI_REPLAY_EXPERIMENT_ID = AttributeKey.stringKey("core_ai.replay_experiment_id");
    private static final AttributeKey<String> CORE_AI_REPLAY_RUN_ID = AttributeKey.stringKey("core_ai.replay_run_id");

    static String truncate(String message) {
        if (message == null) return null;
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : message;
    }

    private final ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);
    private final Map<String, Map<Integer, ReplaySampleCallback>> activeCallbacks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> pendingSamples = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<ReplayRun> runCollection;
    @Inject
    LLMProviders llmProviders;
    @Inject
    TelemetryConfig telemetryConfig;
    @Inject
    ModelPricingService modelPricingService;

    public void submit(ReplayRun run) {
        pendingSamples.put(run.id, new AtomicInteger(run.sampleCount));
        for (var sample : run.samples) {
            int index = sample.index;
            executorService.submit(() -> executeSample(run, index));
        }
    }

    public void cancel(String runId) {
        var callbacks = activeCallbacks.remove(runId);
        if (callbacks != null) {
            callbacks.forEach((index, callback) -> callback.cancelConnection());
        }
        var run = runCollection.get(runId).orElse(null);
        if (run == null || run.samples == null) return;
        var updates = new ArrayList<Bson>();
        for (var sample : run.samples) {
            if (sample.status == ReplaySampleStatus.RUNNING) {
                updates.add(Updates.set("samples." + sample.index + ".status", ReplaySampleStatus.CANCELLED));
            }
        }
        if (!updates.isEmpty()) {
            runCollection.update(Filters.eq("_id", runId), Updates.combine(updates));
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private void executeSample(ReplayRun run, int index) {
        var callback = new ReplaySampleCallback();
        activeCallbacks.computeIfAbsent(run.id, key -> new ConcurrentHashMap<>()).put(index, callback);
        var startedAt = ZonedDateTime.now();
        var startedMs = System.currentTimeMillis();
        var span = startSampleSpan(run, index);
        try (var scope = span.makeCurrent()) {
            var request = buildRequest(run);
            var response = llmProviders.getProvider().completionStream(request, callback, null, true);
            if (callback.isCancelled() || response == null) {
                updateSample(run.id, Updates.set("samples." + index + ".status", ReplaySampleStatus.CANCELLED));
                span.setStatus(StatusCode.ERROR, "cancelled");
            } else {
                var traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
                recordSuccess(new SampleContext(run.id, index, startedAt, startedMs, traceId), request, response);
            }
        } catch (RuntimeException e) {
            if (callback.isCancelled()) {
                updateSample(run.id, Updates.set("samples." + index + ".status", ReplaySampleStatus.CANCELLED));
            } else {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                updateSample(run.id,
                        Updates.set("samples." + index + ".status", ReplaySampleStatus.ERROR),
                        Updates.set("samples." + index + ".error_message", truncate(e.getMessage())));
            }
        } finally {
            span.end();
            var callbacks = activeCallbacks.get(run.id);
            if (callbacks != null) callbacks.remove(index);
            finishSample(run.id);
        }
    }

    private void recordSuccess(SampleContext context, CompletionRequest request,
                               ai.core.llm.domain.CompletionResponse response) {
        var choice = response.choices.isEmpty() ? null : response.choices.getFirst();
        var output = choice != null ? JsonUtil.toJson(choice.message) : null;
        var usage = response.usage;
        Long inputTokens = null;
        Long outputTokens = null;
        Long cachedTokens = null;
        if (usage != null) {
            inputTokens = (long) usage.getPromptTokens();
            outputTokens = (long) usage.getCompletionTokens();
            if (usage.getPromptTokensDetails() != null) {
                cachedTokens = (long) usage.getPromptTokensDetails().cachedTokens;
            }
        }
        var cost = modelPricingService.resolve(request.model, inputTokens, outputTokens, cachedTokens, context.startedAt, null).costUsd();
        var durationMs = System.currentTimeMillis() - context.startedMs;

        var updates = new ArrayList<Bson>();
        updates.add(Updates.set("samples." + context.index + ".status", ReplaySampleStatus.COMPLETED));
        if (output != null) updates.add(Updates.set("samples." + context.index + ".output", output));
        if (inputTokens != null) updates.add(Updates.set("samples." + context.index + ".input_tokens", inputTokens));
        if (outputTokens != null) updates.add(Updates.set("samples." + context.index + ".output_tokens", outputTokens));
        if (cachedTokens != null) updates.add(Updates.set("samples." + context.index + ".cached_tokens", cachedTokens));
        if (cost != null) updates.add(Updates.set("samples." + context.index + ".cost_usd", cost));
        updates.add(Updates.set("samples." + context.index + ".duration_ms", durationMs));
        if (context.traceId != null) updates.add(Updates.set("samples." + context.index + ".replay_trace_id", context.traceId));
        updateSample(context.runId, updates.toArray(new Bson[0]));
    }

    CompletionRequest buildRequest(ReplayRun run) {
        var request = ReplayRequestCodec.parse(run.request);
        if (!Strings.isBlank(run.model)) request.model = run.model;
        if (run.temperature != null) request.temperature = run.temperature;
        if (!Strings.isBlank(run.reasoningEffort)) {
            var effort = ReasoningEffort.fromString(run.reasoningEffort);
            if (effort != null) request.reasoningEffort = effort;
        }
        request.setTimeoutSeconds(SAMPLE_TIMEOUT_SECONDS);
        return request;
    }

    private Span startSampleSpan(ReplayRun run, int index) {
        return telemetryConfig.getOpenTelemetry().getTracer("core-ai-server", "1.0.0")
                .spanBuilder("replay.sample")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(CLIENT_TYPE, "replay")
                .setAttribute(CORE_AI_REPLAY_EXPERIMENT_ID, run.experimentId)
                .setAttribute(CORE_AI_REPLAY_RUN_ID, run.id)
                .setAttribute("replay.sample_index", (long) index)
                .startSpan();
    }

    private void updateSample(String runId, Bson... updates) {
        runCollection.update(Filters.eq("_id", runId), Updates.combine(updates));
    }

    private void finishSample(String runId) {
        var pending = pendingSamples.get(runId);
        if (pending == null) return;
        if (pending.decrementAndGet() > 0) return;
        pendingSamples.remove(runId);
        var run = runCollection.get(runId).orElse(null);
        if (run == null || run.samples == null || run.samples.isEmpty()) return;
        runCollection.update(Filters.eq("_id", runId), Updates.combine(
                Updates.set("status", aggregate(run.samples)),
                Updates.set("completed_at", ZonedDateTime.now())));
    }

    ReplayRunStatus aggregate(List<ReplaySample> samples) {
        if (samples.stream().anyMatch(sample -> sample.status == ReplaySampleStatus.RUNNING)) return ReplayRunStatus.RUNNING;
        if (samples.stream().allMatch(sample -> sample.status == ReplaySampleStatus.CANCELLED)) return ReplayRunStatus.CANCELLED;
        if (samples.stream().allMatch(sample -> sample.status == ReplaySampleStatus.COMPLETED)) return ReplayRunStatus.COMPLETED;
        if (samples.stream().anyMatch(sample -> sample.status == ReplaySampleStatus.COMPLETED)) return ReplayRunStatus.PARTIAL;
        return ReplayRunStatus.ERROR;
    }

    private record SampleContext(String runId, int index, ZonedDateTime startedAt, long startedMs, String traceId) {
    }

    private static final class ReplaySampleCallback extends DefaultStreamingCallback {
        private volatile boolean cancelled;
        private volatile AutoCloseable connection;

        @Override
        public void setActiveConnection(AutoCloseable connection) {
            this.connection = connection;
        }

        @Override
        public void cancelConnection() {
            cancelled = true;
            var active = connection;
            if (active != null) {
                try {
                    active.close();
                } catch (Exception e) {
                    LOGGER.warn("failed to close replay sample connection", e);
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
