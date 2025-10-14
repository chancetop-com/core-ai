package ai.core.telemetry;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.utils.JsonUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

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
    private static final AttributeKey<String> GEN_AI_RESPONSE_FINISH_REASON = AttributeKey.stringKey("gen_ai.response.finish_reasons");

    // Attribute keys for input/output (Langfuse expects these as attributes)
    private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");

    public LLMTracer(OpenTelemetry openTelemetry, boolean enabled) {
        super(openTelemetry, enabled);
    }

    /**
     * Convenience method to trace LLM completion with request/response directly
     * Creates context internally and updates it from the response
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    public CompletionResponse traceLLMCompletion(String providerName, CompletionRequest request, Supplier<CompletionResponse> operation) {
        if (!enabled) {
            return operation.get();
        }

        var requestAttributes = buildRequestAttributes(providerName, request);
        var span = tracer.spanBuilder(providerName)
            .setSpanKind(SpanKind.CLIENT)
            .setAllAttributes(requestAttributes)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "generation")
            .startSpan();

        // Add input as attribute for Langfuse
        if (request.messages != null && !request.messages.isEmpty()) {
            span.setAttribute(GEN_AI_PROMPT, serializeMessagesToJson(request.messages));
        }

        try (var scope = span.makeCurrent()) {
            var response = operation.get();
            recordResponseAttributes(span, response);

            // Add output as attribute for Langfuse
            if (!response.choices.isEmpty() && response.choices.getFirst().message != null) {
                span.setAttribute(GEN_AI_COMPLETION, serializeMessageToJson(response.choices.getFirst().message));
            }

            return response;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Build request attributes from completion request
     */
    private Attributes buildRequestAttributes(String providerName, CompletionRequest request) {
        var builder = Attributes.builder()
            .put(GEN_AI_OPERATION_NAME, "chat")
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
    private void recordResponseAttributes(Span span, CompletionResponse response) {
        if (response.usage != null) {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) response.usage.getPromptTokens());
            span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) response.usage.getCompletionTokens());
        }

        if (!response.choices.isEmpty() && response.choices.getFirst().finishReason != null) {
            span.setAttribute(GEN_AI_RESPONSE_FINISH_REASON, response.choices.getFirst().finishReason.name());
        }
    }

    /**
     * Serialize message list to JSON with fallback to string format
     */
    private String serializeMessagesToJson(java.util.List<ai.core.llm.domain.Message> messages) {
        try {
            return truncate(JsonUtil.toJson(messages), 10000);
        } catch (Exception e) {
            // Fallback to string format if JSON serialization fails
            var builder = new StringBuilder();
            for (var message : messages) {
                if (message.content != null) {
                    builder.append(message.role.name()).append(": ").append(message.content).append('\n');
                }
            }
            return truncate(builder.toString(), 4000);
        }
    }

    /**
     * Serialize message to JSON with fallback to plain content
     */
    private String serializeMessageToJson(ai.core.llm.domain.Message message) {
        try {
            return truncate(JsonUtil.toJson(message), 10000);
        } catch (Exception e) {
            // Fallback to plain content if JSON serialization fails
            return message.content != null ? truncate(message.content, 4000) : "";
        }
    }
}
