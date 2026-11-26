package ai.core.server.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Records the LLM span attributes of a gateway proxy call: upstream status, token usage and the
 * assistant output extracted from non-streaming bodies or streaming events.
 */
final class GatewayProxyTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayProxyTrace.class);
    private static final AttributeKey<String> LANGFUSE_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_CACHED_TOKENS = AttributeKey.longKey("gen_ai.usage.cached_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_REASONING_TOKENS = AttributeKey.longKey("gen_ai.usage.reasoning_tokens");
    private static final AttributeKey<Long> GEN_AI_RESPONSE_TTFT_MS = AttributeKey.longKey("gen_ai.response.time_to_first_token_ms");
    private static final AttributeKey<Long> GEN_AI_RESPONSE_FIRST_CONTENT_MS = AttributeKey.longKey("gen_ai.response.first_content_ms");
    // cap the span output so a very long streaming response cannot inflate the stored span
    private static final int MAX_SPAN_OUTPUT_LENGTH = 200_000;

    static void output(Span span, String output) {
        if (output != null && !output.isEmpty()) span.setAttribute(LANGFUSE_OUTPUT, output);
    }

    static void markUpstreamStatus(Span span, int statusCode) {
        if (statusCode >= 400) span.setStatus(StatusCode.ERROR, "upstream returned " + statusCode);
        else span.setStatus(StatusCode.OK);
    }

    static void recordUsage(Span span, byte[] body) {
        if (body == null || body.length == 0) return;
        try {
            applyUsage(span, GatewaySupport.parseUsage(GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("response body is not JSON with usage, usage stays unknown", e);
        }
    }

    static void recordStreamUsage(Span span, String data) {
        if (data == null || !data.contains("usage")) return;
        try {
            applyUsage(span, GatewaySupport.parseUsage(GatewayJson.MAPPER.readValue(data, GatewaySupport.MAP_TYPE)));
        } catch (Exception e) {
            LOGGER.debug("stream chunk is not a JSON usage payload", e);
        }
    }

    // Streaming chat chunks carry the assistant text in choices[].delta.content (or choices[].message.content),
    // the responses endpoint streams it as response.output_text.delta events and anthropic as
    // content_block_delta/text_delta; concatenate every shape so the span output holds the final answer.
    static StreamText appendStreamOutput(StringBuilder output, String data) {
        if (data == null || "[DONE]".equals(data) || output.length() >= MAX_SPAN_OUTPUT_LENGTH) return StreamText.NONE;
        var body = parseStreamChunk(data);
        if (body == null) return StreamText.NONE;
        var content = chatChunkContent(body);
        if (content == null) content = responsesDeltaText(body);
        if (content == null) content = anthropicDeltaText(body);
        if (content != null && !content.isBlank()) {
            output.append(content);
            return StreamText.CONTENT;
        }
        return hasReasoningText(body) ? StreamText.REASONING : StreamText.NONE;
    }

    static void recordStreamText(TimeToFirstToken ttft, Span span, StringBuilder output, String data) {
        var text = appendStreamOutput(output, data);
        if (text != StreamText.NONE) ttft.recordToken(span);
        if (text == StreamText.CONTENT) ttft.recordContent(span);
    }

    private static boolean hasReasoningText(Map<String, Object> body) {
        if (body.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice
                && (hasReasoningText(choice.get("delta")) || hasReasoningText(choice.get("message")))) {
            return true;
        }
        var type = body.get("type");
        if ("response.reasoning_summary_text.delta".equals(type) || "response.reasoning_text.delta".equals(type)) {
            return true;
        }
        return "content_block_delta".equals(type) && body.get("delta") instanceof Map<?, ?> delta
                && "thinking_delta".equals(delta.get("type"));
    }

    private static boolean hasReasoningText(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        var reasoning = map.get("reasoning_content");
        if (reasoning == null) reasoning = map.get("reasoning");
        return reasoning instanceof String text && !text.isBlank();
    }

    private static void applyUsage(Span span, GatewayUsage usage) {
        if (usage == null) return;
        // anthropic streams report input usage in message_start and output usage in message_delta;
        // never zero a value another event already reported
        if (usage.inputTokens() > 0) span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, usage.inputTokens());
        if (usage.outputTokens() > 0) span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, usage.outputTokens());
        if (usage.cachedTokens() > 0) span.setAttribute(GEN_AI_USAGE_CACHED_TOKENS, usage.cachedTokens());
        if (usage.reasoningTokens() > 0) span.setAttribute(GEN_AI_USAGE_REASONING_TOKENS, usage.reasoningTokens());
    }

    private static Map<String, Object> parseStreamChunk(String data) {
        try {
            return GatewayJson.MAPPER.readValue(data, GatewaySupport.MAP_TYPE);
        } catch (JsonProcessingException e) {
            // non-JSON chunk (e.g. keepalive), nothing to record
            LOGGER.debug("stream chunk is not a JSON payload, skipped from span output", e);
            return null;
        }
    }

    private static String chatChunkContent(Map<String, Object> body) {
        if (!(body.get("choices") instanceof List<?> choices) || choices.isEmpty()) return null;
        if (!(choices.getFirst() instanceof Map<?, ?> choice)) return null;
        var content = contentText(choice.get("delta"));
        return content == null ? contentText(choice.get("message")) : content;
    }

    private static String responsesDeltaText(Map<String, Object> body) {
        if (!"response.output_text.delta".equals(body.get("type"))) return null;
        return body.get("delta") instanceof String delta ? delta : null;
    }

    private static String anthropicDeltaText(Map<String, Object> body) {
        if (!"content_block_delta".equals(body.get("type"))) return null;
        if (!(body.get("delta") instanceof Map<?, ?> delta) || !"text_delta".equals(delta.get("type"))) return null;
        return delta.get("text") instanceof String text ? text : null;
    }

    private static String contentText(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return map.get("content") instanceof String text ? text : null;
    }

    private GatewayProxyTrace() {
    }

    /**
     * What a streamed event carried: answer text is appended to the span output, reasoning text only
     * matters for the first-token measurement (reasoning models can think for minutes before answering).
     */
    enum StreamText {
        NONE, REASONING, CONTENT
    }

    /**
     * Wall clock since the upstream request was issued. Two milestones are recorded: the first streamed
     * token of any kind (what a user waits for) and the first answer-content token, after which the
     * answer itself is generated. Non-streaming calls have neither, so callers only use this on
     * streamed responses.
     */
    static final class TimeToFirstToken {
        private final long startNanos = System.nanoTime();
        private boolean tokenRecorded;
        private boolean contentRecorded;

        void recordToken(Span span) {
            if (tokenRecorded) return;
            tokenRecorded = true;
            record(span, GEN_AI_RESPONSE_TTFT_MS);
        }

        void recordContent(Span span) {
            if (contentRecorded) return;
            contentRecorded = true;
            record(span, GEN_AI_RESPONSE_FIRST_CONTENT_MS);
        }

        private void record(Span span, AttributeKey<Long> key) {
            if (!span.getSpanContext().isValid()) return;
            span.setAttribute(key, Math.max(0, (System.nanoTime() - startNanos) / 1_000_000));
        }
    }
}
