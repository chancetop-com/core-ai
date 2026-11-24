package ai.core.telemetry;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.utils.JsonUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tracer for LLM-specific operations
 * Adds LLM domain attributes to traces using LLMTraceContext
 * Follows OpenTelemetry Semantic Conventions for GenAI
 *
 * @author stephen
 */
public class LLMTracer extends Tracer {
    // GenAI semantic convention attributes
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Double> GEN_AI_REQUEST_TEMPERATURE = AttributeKey.doubleKey("gen_ai.request.temperature");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_CACHED_TOKENS = AttributeKey.longKey("gen_ai.usage.cached_tokens");
    private static final AttributeKey<Double> GEN_AI_USAGE_COST_USD = AttributeKey.doubleKey("gen_ai.usage.cost_usd");
    private static final AttributeKey<String> GEN_AI_RESPONSE_FINISH_REASON = AttributeKey.stringKey("gen_ai.response.finish_reasons");
    private static final AttributeKey<Long> GEN_AI_RESPONSE_TTFT_MS = AttributeKey.longKey("gen_ai.response.time_to_first_token_ms");
    private static final AttributeKey<Boolean> CORE_AI_RESPONSE_NULL = AttributeKey.booleanKey("core_ai.response.null");

    // Trace is a diagnostic side channel: it must never become a second copy of the request. The input
    // attribute is capped, oversized data URIs are replaced before serialization (so the giant JSON is
    // never allocated), and the cap is marked on the span so a truncated view is not mistaken for the
    // real request.
    private static final int MAX_SPAN_INPUT_BYTES = 256 * 1024;
    private static final int MAX_INLINE_TRACE_IMAGE_BYTES = 32 * 1024;
    private static final int MAX_TRACE_TEXT_CHARS = 32 * 1024;
    private static final int SUMMARY_TEXT_CHARS = 2 * 1024;
    private static final int COMPACT_SUMMARY_CHARS = 32 * 1024;
    private static final AttributeKey<Boolean> CORE_AI_TRACE_INPUT_TRUNCATED =
            AttributeKey.booleanKey("coreai.trace.input_truncated");
    private static final AttributeKey<Long> CORE_AI_TRACE_INPUT_BYTES = AttributeKey.longKey("coreai.trace.input_bytes");

    // Langfuse-specific attributes (maps directly to Langfuse data model)
    // langfuse.observation.input: Full request including messages and tools (enables Playground integration)
    // langfuse.observation.output: Response from LLM
    // langfuse.observation.model.parameters: Model parameters like temperature
    private static final AttributeKey<String> LANGFUSE_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> LANGFUSE_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> LANGFUSE_MODEL_PARAMETERS = AttributeKey.stringKey("langfuse.observation.model.parameters");

    public LLMTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Convenience method to trace LLM completion with request/response directly
     * Creates context internally and updates it from the response
     */
    public CompletionResponse traceLLMCompletion(String providerName, CompletionRequest request, Supplier<CompletionResponse> operation) {
        return traceLLMCompletion(providerName, request, operation, null, null);
    }

    /**
     * Trace LLM completion with an optional sink that receives the started span's context.
     * Used by callers (e.g. Agent) to remember which LLM call triggered subsequent tool calls.
     */
    public CompletionResponse traceLLMCompletion(String providerName, CompletionRequest request,
                                                 Supplier<CompletionResponse> operation,
                                                 Consumer<SpanContext> spanContextSink) {
        return traceLLMCompletion(providerName, request, operation, spanContextSink, null);
    }

    /**
     * Trace LLM completion and mark the span as user-cancelled when the caller reports cancellation.
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public CompletionResponse traceLLMCompletion(String providerName, CompletionRequest request,
                                                  Supplier<CompletionResponse> operation,
                                                  Consumer<SpanContext> spanContextSink,
                                                  BooleanSupplier cancellationSupplier) {
        if (!enabled) {
            return operation.get();
        }
        var span = createLLMSpan(providerName, request, spanContextSink);
        CompletionResponse response;
        try (var scope = span.makeCurrent()) {
            response = operation.get();
            if (response == null) {
                span.setAttribute(CORE_AI_RESPONSE_NULL, Boolean.TRUE);
                if (isCancelled(cancellationSupplier)) {
                    markCancelled(span);
                    return null;
                }
                span.setStatus(StatusCode.ERROR, "LLM provider returned null completion response");
            } else {
                recordSuccessResponse(span, request, response, cancellationSupplier);
            }
        } catch (Exception e) {
            if (isCancelled(cancellationSupplier)) {
                markCancelled(span);
            } else {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            span.end();
        }
        if (response == null) {
            throw new IllegalStateException("LLM provider returned null completion response");
        }
        return response;
    }

    private Span createLLMSpan(String providerName, CompletionRequest request, Consumer<SpanContext> sink) {
        var spanName = request.getName() != null ? request.getName() : providerName;
        var span = tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.CLIENT)
            .setAllAttributes(buildRequestAttributes(providerName, request))
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "generation")
            .startSpan();
        if (sink != null) {
            sink.accept(span.getSpanContext());
        }
        var input = buildInputJson(request);
        span.setAttribute(LANGFUSE_INPUT, input.json());
        span.setAttribute(CORE_AI_TRACE_INPUT_BYTES, (long) input.bytes());
        if (input.truncated()) {
            span.setAttribute(CORE_AI_TRACE_INPUT_TRUNCATED, Boolean.TRUE);
        }
        span.setAttribute(LANGFUSE_MODEL_PARAMETERS, buildModelParametersJson(request));
        return span;
    }

    private void recordSuccessResponse(Span span, CompletionRequest request, CompletionResponse response,
                                       BooleanSupplier cancellationSupplier) {
        recordResponseAttributes(span, request, response);
        if (isCancelled(cancellationSupplier)) {
            markCancelled(span);
        }
        var message = firstMessage(response);
        if (message != null) {
            span.setAttribute(LANGFUSE_OUTPUT, serializeMessageToJson(message));
        }
    }

    /**
     * Build request attributes from completion request
     */
    private Attributes buildRequestAttributes(String providerName, CompletionRequest request) {
        var operationName = request.getName() != null ? request.getName() : "chat";
        var builder = Attributes.builder()
            .put(GEN_AI_OPERATION_NAME, operationName)
            .put(GEN_AI_SYSTEM, providerName)
            .put(GEN_AI_REQUEST_MODEL, request.model != null ? request.model : "default");

        if (request.temperature != null) {
            builder.put(GEN_AI_REQUEST_TEMPERATURE, request.temperature);
        }

        return builder.build();
    }

    /**
     * Record response attributes on the span
     */
    private void recordResponseAttributes(Span span, CompletionRequest request, CompletionResponse response) {
        if (response == null) {
            span.setAttribute(CORE_AI_RESPONSE_NULL, Boolean.TRUE);
            return;
        }
        if (response.usage != null) {
            var inputTokens = response.usage.getPromptTokens();
            var outputTokens = response.usage.getCompletionTokens();
            var cachedTokens = response.usage.getPromptTokensDetails() != null
                ? (long) response.usage.getPromptTokensDetails().cachedTokens
                : 0L;
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, inputTokens);
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, outputTokens);
            span.setAttribute(GEN_AI_USAGE_CACHED_TOKENS, cachedTokens);
            var cost = LLMModelContextRegistry.getInstance().estimateCostUsd(
                request.model, inputTokens, outputTokens, cachedTokens, Instant.now());
            if (cost != null) {
                span.setAttribute(GEN_AI_USAGE_COST_USD, cost);
            }
        }

        var choice = firstChoice(response);
        if (choice != null && choice.finishReason != null) {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASON, choice.finishReason.name());
        }
        if (response.ttftMs != null) {
            span.setAttribute(GEN_AI_RESPONSE_TTFT_MS, response.ttftMs);
        }
    }

    private ai.core.llm.domain.Choice firstChoice(CompletionResponse response) {
        if (response.choices == null || response.choices.isEmpty()) {
            return null;
        }
        return response.choices.getFirst();
    }

    private ai.core.llm.domain.AssistantMessage firstMessage(CompletionResponse response) {
        var choice = firstChoice(response);
        if (choice == null) {
            return null;
        }
        return choice.message;
    }

    /**
     * Serialize message to JSON with fallback to plain content
     */
    private String serializeMessageToJson(ai.core.llm.domain.AssistantMessage message) {
        try {
            return JsonUtil.toJson(message);
        } catch (Exception e) {
            // Fallback to plain content if JSON serialization fails
            return message.content != null ? message.content : "";
        }
    }

    /**
     * Build input JSON for Langfuse (OpenAI ChatML format)
     * This includes messages and tools to enable Playground integration
     * Langfuse parses this to extract tool definitions for the Playground
     */
    private TraceInput buildInputJson(CompletionRequest request) {
        try {
            var messages = traceMessages(request.messages);
            var json = serializeInput(request, messages);
            var bytes = utf8Length(json);
            if (bytes <= MAX_SPAN_INPUT_BYTES) return new TraceInput(json, false, bytes);
            var summary = serializeSummary(request, messages);
            var summaryBytes = utf8Length(summary);
            if (summaryBytes <= MAX_SPAN_INPUT_BYTES) return new TraceInput(summary, true, summaryBytes);
            var compact = compactSummary(messages);
            return new TraceInput(compact, true, utf8Length(compact));
        } catch (RuntimeException e) {
            var compact = compactSummary(request.messages);
            return new TraceInput(compact, true, utf8Length(compact));
        }
    }

    private String serializeInput(CompletionRequest request, List<ai.core.llm.domain.Message> messages) {
        var input = new LinkedHashMap<String, Object>();
        if (messages != null && !messages.isEmpty()) {
            input.put("messages", messages);
        }
        if (request.tools != null && !request.tools.isEmpty()) {
            input.put("tools", request.tools);
        }
        if (request.toolChoice != null) {
            input.put("tool_choice", request.toolChoice);
        }
        return JsonUtil.toJson(input);
    }

    /**
     * Replaces the content the trace must not copy before any JSON is produced: an oversized data URI
     * becomes a short placeholder instead of a multi-megabyte string in the span, and an oversized text
     * part is head/tail truncated. The request itself is never touched.
     */
    private List<ai.core.llm.domain.Message> traceMessages(List<ai.core.llm.domain.Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        List<ai.core.llm.domain.Message> sanitized = null;
        for (var i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            var copy = traceMessage(message);
            if (copy == message) continue;
            if (sanitized == null) sanitized = new ArrayList<>(messages);
            sanitized.set(i, copy);
        }
        return sanitized == null ? messages : sanitized;
    }

    private ai.core.llm.domain.Message traceMessage(ai.core.llm.domain.Message message) {
        if (message == null) return null;
        var content = traceContent(message.content);
        var reasoning = truncateForTrace(message.reasoningContent, MAX_TRACE_TEXT_CHARS);
        if (content == message.content && reasoning == message.reasoningContent) return message;
        var copy = new ai.core.llm.domain.Message();
        copy.role = message.role;
        copy.content = content;
        copy.reasoningContent = reasoning;
        copy.name = message.name;
        copy.toolCallId = message.toolCallId;
        copy.toolCalls = message.toolCalls;
        return copy;
    }

    private List<Content> traceContent(List<Content> content) {
        if (content == null || content.isEmpty()) return content;
        List<Content> sanitized = null;
        for (var i = 0; i < content.size(); i++) {
            var part = content.get(i);
            var copy = traceContent(part);
            if (copy == part) continue;
            if (sanitized == null) sanitized = new ArrayList<>(content);
            sanitized.set(i, copy);
        }
        return sanitized == null ? content : sanitized;
    }

    private Content traceContent(Content part) {
        if (part == null || part.type == null) return part;
        if (part.type == Content.ContentType.IMAGE_URL && part.imageUrl != null && part.imageUrl.url != null
                && part.imageUrl.url.startsWith("data:")) {
            var bytes = decodedBytes(part.imageUrl.url);
            if (bytes > MAX_INLINE_TRACE_IMAGE_BYTES) {
                return Content.of(String.format("[image omitted from trace: %d bytes]", bytes));
            }
            return part;
        }
        if (part.type == Content.ContentType.FILE && part.file != null && part.file.fileData != null
                && part.file.fileData.startsWith("data:") && part.file.fileData.length() > MAX_INLINE_TRACE_IMAGE_BYTES) {
            return Content.of(String.format("[file omitted from trace: %d bytes]", decodedBytes(part.file.fileData)));
        }
        if (part.type == Content.ContentType.TEXT) {
            var text = truncateForTrace(part.text, MAX_TRACE_TEXT_CHARS);
            if (text != part.text) return Content.of(text);
        }
        return part;
    }

    /**
     * Second level: keep the shape (roles, short text, tool names) when the full input is over the cap.
     */
    private String serializeSummary(CompletionRequest request, List<ai.core.llm.domain.Message> messages) {
        var input = new LinkedHashMap<String, Object>();
        var summaries = new ArrayList<LinkedHashMap<String, Object>>();
        if (messages != null) {
            for (var message : messages) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("role", message.role == null ? "unknown" : message.role.name().toLowerCase(Locale.ROOT));
                var text = truncateForTrace(message.getJoinedTextContent(), SUMMARY_TEXT_CHARS);
                if (text != null) {
                    entry.put("content", text);
                }
                if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
                    entry.put("tool_calls", message.toolCalls.stream()
                            .map(call -> call == null || call.function == null ? null : call.function.name)
                            .filter(Objects::nonNull)
                            .toList());
                }
                summaries.add(entry);
            }
        }
        input.put("messages", summaries);
        if (request.tools != null && !request.tools.isEmpty()) {
            input.put("tools", request.tools.stream()
                    .map(tool -> tool == null || tool.function == null ? null : tool.function.name)
                    .filter(Objects::nonNull)
                    .toList());
        }
        input.put("truncated", Boolean.TRUE);
        return JsonUtil.toJson(input);
    }

    /**
     * Last level: a single bounded string, so the span attribute cannot grow past the cap even with a
     * pathological number of messages. Always valid JSON.
     */
    private String compactSummary(List<ai.core.llm.domain.Message> messages) {
        var text = new StringBuilder();
        if (messages != null) {
            for (var message : messages) {
                text.append(message.role == null ? "unknown" : message.role.name().toLowerCase(Locale.ROOT))
                        .append(": ")
                        .append(truncateForTrace(message.getJoinedTextContent(), SUMMARY_TEXT_CHARS))
                        .append('\n');
                if (text.length() > COMPACT_SUMMARY_CHARS) break;
            }
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("summary", truncateForTrace(text.toString(), COMPACT_SUMMARY_CHARS));
        input.put("truncated", Boolean.TRUE);
        return JsonUtil.toJson(input);
    }

    private static String truncateForTrace(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        var head = maxChars * 2 / 3;
        var tail = maxChars - head;
        return text.substring(0, head) + "…[truncated " + (text.length() - maxChars) + " chars]…"
                + text.substring(text.length() - tail);
    }

    private static long decodedBytes(String dataUri) {
        var comma = dataUri.indexOf(',');
        if (comma < 0) return 0;
        return (dataUri.length() - comma - 1L) / 4 * 3;
    }

    private static int utf8Length(CharSequence value) {
        var bytes = 0;
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            if (ch < 0x80) {
                bytes += 1;
            } else if (ch < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private record TraceInput(String json, boolean truncated, int bytes) {
    }

    /**
     * Build model parameters JSON for Langfuse
     * Contains model configuration parameters (temperature, etc.)
     */
    private String buildModelParametersJson(CompletionRequest request) {
        try {
            var params = new LinkedHashMap<String, Object>();

            if (request.temperature != null) {
                params.put("temperature", request.temperature);
            }
            if (request.responseFormat != null) {
                params.put("response_format", request.responseFormat);
            }
            if (request.reasoningEffort != null) {
                params.put("reasoning_effort", request.reasoningEffort);
            }

            return JsonUtil.toJson(params);
        } catch (RuntimeException e) {
            return "{}";
        }
    }
}
