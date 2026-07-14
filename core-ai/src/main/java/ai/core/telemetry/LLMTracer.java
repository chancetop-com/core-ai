package ai.core.telemetry;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.utils.JsonUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.LinkedHashMap;
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
    private static final AttributeKey<Boolean> CORE_AI_RESPONSE_NULL = AttributeKey.booleanKey("core_ai.response.null");

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
        span.setAttribute(LANGFUSE_INPUT, buildInputJson(request));
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
                request.model, inputTokens, outputTokens, cachedTokens);
            if (cost != null) {
                span.setAttribute(GEN_AI_USAGE_COST_USD, cost);
            }
        }

        var choice = firstChoice(response);
        if (choice != null && choice.finishReason != null) {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASON, choice.finishReason.name());
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
     * Serialize message list to JSON with fallback to string format
     */
    private String serializeMessagesToJson(java.util.List<ai.core.llm.domain.Message> messages) {
        try {
            return JsonUtil.toJson(messages);
        } catch (Exception e) {
            // Fallback to string format if JSON serialization fails
            var builder = new StringBuilder();
            for (var message : messages) {
                if (message.content != null) {
                    builder.append(message.role.name()).append(": ").append(message.content).append('\n');
                }
            }
            return builder.toString();
        }
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
    private String buildInputJson(CompletionRequest request) {
        try {
            var input = new LinkedHashMap<String, Object>();

            if (request.messages != null && !request.messages.isEmpty()) {
                input.put("messages", request.messages);
            }
            if (request.tools != null && !request.tools.isEmpty()) {
                input.put("tools", request.tools);
            }
            if (request.toolChoice != null) {
                input.put("tool_choice", request.toolChoice);
            }

            return JsonUtil.toJson(input);
        } catch (RuntimeException e) {
            // Fallback to messages only
            return serializeMessagesToJson(request.messages);
        }
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

            return JsonUtil.toJson(params);
        } catch (RuntimeException e) {
            return "{}";
        }
    }
}
