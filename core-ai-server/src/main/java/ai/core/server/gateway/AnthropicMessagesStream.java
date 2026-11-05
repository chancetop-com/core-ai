package ai.core.server.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat completions stream -> Anthropic messages stream. One upstream chunk may produce
 * several downstream events (a text delta after closing a tool block, for example).
 */
final class AnthropicMessagesStream {
    private final String requestedModel;
    private final Map<Integer, Integer> toolBlocks = new LinkedHashMap<>();
    private boolean started;
    private boolean finished;
    private int nextIndex;
    private Integer openIndex;
    private boolean openText;
    private String stopReason;
    private long inputTokens;
    private long outputTokens;

    AnthropicMessagesStream(String requestedModel) {
        this.requestedModel = requestedModel;
    }

    List<SseEvent> onEvent(String event, String data) {
        if (data == null || data.isBlank()) return List.of();
        if ("[DONE]".equals(data.trim())) return finish();
        var chunk = AnthropicMessagesProtocol.parse(data);
        if (chunk == null) return List.of();
        var events = new ArrayList<SseEvent>();
        if (chunk.get("error") != null) {
            events.add(new SseEvent("error", AnthropicMessagesProtocol.json(AnthropicMessagesProtocol.toErrorResponse(500, chunk))));
            finished = true;
            return events;
        }
        captureUsage(chunk);
        startMessage(chunk, events);
        if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()) return events;
        if (!(choices.getFirst() instanceof Map<?, ?> choice)) return events;
        var delta = choice.get("delta") instanceof Map<?, ?> map ? map
                : choice.get("message") instanceof Map<?, ?> map ? map : Map.of();
        appendContent(delta, events);
        appendToolCalls(delta, events);
        captureFinishReason(choice);
        return events;
    }

    List<SseEvent> finish() {
        if (finished) return List.of();
        var events = new ArrayList<SseEvent>();
        startMessage(Map.of(), events);
        closeOpenBlock(events);
        var delta = new LinkedHashMap<String, Object>();
        delta.put("type", "message_delta");
        var stop = new LinkedHashMap<String, Object>();
        stop.put("stop_reason", stopReason != null ? stopReason : toolBlocks.isEmpty() ? "end_turn" : "tool_use");
        stop.put("stop_sequence", null);
        delta.put("delta", stop);
        delta.put("usage", Map.of("output_tokens", outputTokens));
        events.add(new SseEvent("message_delta", AnthropicMessagesProtocol.json(delta)));
        events.add(new SseEvent("message_stop", "{\"type\":\"message_stop\"}"));
        finished = true;
        return events;
    }

    private void startMessage(Map<String, Object> chunk, List<SseEvent> events) {
        if (started) return;
        started = true;
        var messageId = AnthropicMessagesProtocol.hasText(AnthropicMessagesProtocol.string(chunk.get("id")))
                ? AnthropicMessagesProtocol.string(chunk.get("id")) : AnthropicMessagesProtocol.newId("msg_");
        var usage = new LinkedHashMap<String, Object>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        var message = new LinkedHashMap<String, Object>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", requestedModel);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage);
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "message_start");
        event.put("message", message);
        events.add(new SseEvent("message_start", AnthropicMessagesProtocol.json(event)));
    }

    private void captureUsage(Map<String, Object> chunk) {
        if (!(chunk.get("usage") instanceof Map<?, ?> usage)) return;
        inputTokens = Math.max(inputTokens, AnthropicMessagesProtocol.number(usage.get("prompt_tokens"),
                AnthropicMessagesProtocol.number(usage.get("input_tokens"), inputTokens)));
        outputTokens = Math.max(outputTokens, AnthropicMessagesProtocol.number(usage.get("completion_tokens"),
                AnthropicMessagesProtocol.number(usage.get("output_tokens"), outputTokens)));
    }

    private void appendContent(Map<?, ?> delta, List<SseEvent> events) {
        var content = AnthropicMessagesProtocol.string(delta.get("content"));
        if (content == null || content.isEmpty()) return;
        openTextBlock(events);
        events.add(new SseEvent("content_block_delta",
                AnthropicMessagesProtocol.json(deltaEvent(openIndex, "text_delta", "text", content))));
    }

    private void appendToolCalls(Map<?, ?> delta, List<SseEvent> events) {
        if (!(delta.get("tool_calls") instanceof List<?> toolCalls)) return;
        for (var toolCall : toolCalls) {
            if (toolCall instanceof Map<?, ?> call) openToolCall(call, events);
        }
    }

    private void captureFinishReason(Map<?, ?> choice) {
        var finishReason = AnthropicMessagesProtocol.string(choice.get("finish_reason"));
        if (!AnthropicMessagesProtocol.hasText(finishReason)) return;
        stopReason = AnthropicMessagesProtocol.anthropicStopReason(finishReason, List.of());
        // some providers end a tool-calling turn with finish_reason=stop
        if ("end_turn".equals(stopReason) && !toolBlocks.isEmpty()) stopReason = "tool_use";
    }

    private void openTextBlock(List<SseEvent> events) {
        if (openIndex != null && openText) return;
        closeOpenBlock(events);
        openIndex = nextIndex++;
        openText = true;
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "content_block_start");
        block.put("index", openIndex);
        block.put("content_block", Map.of("type", "text", "text", ""));
        events.add(new SseEvent("content_block_start", AnthropicMessagesProtocol.json(block)));
    }

    private void openToolCall(Map<?, ?> call, List<SseEvent> events) {
        var toolIndex = (int) AnthropicMessagesProtocol.number(call.get("index"), toolBlocks.size());
        var function = call.get("function") instanceof Map<?, ?> map ? map : Map.of();
        var blockIndex = toolBlocks.get(toolIndex);
        if (blockIndex == null) {
            closeOpenBlock(events);
            blockIndex = nextIndex++;
            toolBlocks.put(toolIndex, blockIndex);
            openIndex = blockIndex;
            openText = false;
            events.add(new SseEvent("content_block_start",
                    AnthropicMessagesProtocol.json(toolUseStart(blockIndex, call, function))));
        }
        var arguments = AnthropicMessagesProtocol.string(function.get("arguments"));
        if (!AnthropicMessagesProtocol.hasText(arguments)) return;
        var delta = new LinkedHashMap<String, Object>();
        delta.put("type", "content_block_delta");
        delta.put("index", blockIndex);
        delta.put("delta", Map.of("type", "input_json_delta", "partial_json", arguments));
        events.add(new SseEvent("content_block_delta", AnthropicMessagesProtocol.json(delta)));
    }

    private Map<String, Object> toolUseStart(Integer blockIndex, Map<?, ?> call, Map<?, ?> function) {
        var contentBlock = new LinkedHashMap<String, Object>();
        contentBlock.put("type", "tool_use");
        contentBlock.put("id", AnthropicMessagesProtocol.hasText(AnthropicMessagesProtocol.string(call.get("id")))
                ? AnthropicMessagesProtocol.string(call.get("id")) : AnthropicMessagesProtocol.newId("toolu_"));
        contentBlock.put("name", AnthropicMessagesProtocol.hasText(AnthropicMessagesProtocol.string(function.get("name")))
                ? AnthropicMessagesProtocol.string(function.get("name")) : "tool");
        contentBlock.put("input", Map.of());
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "content_block_start");
        event.put("index", blockIndex);
        event.put("content_block", contentBlock);
        return event;
    }

    private void closeOpenBlock(List<SseEvent> events) {
        if (openIndex == null) return;
        var event = new LinkedHashMap<String, Object>();
        event.put("type", "content_block_stop");
        event.put("index", openIndex);
        events.add(new SseEvent("content_block_stop", AnthropicMessagesProtocol.json(event)));
        openIndex = null;
        openText = false;
    }

    private Map<String, Object> deltaEvent(Integer index, String deltaType, String field, String value) {
        var delta = new LinkedHashMap<String, Object>();
        delta.put("type", "content_block_delta");
        delta.put("index", index);
        delta.put("delta", Map.of("type", deltaType, field, value));
        return delta;
    }

    record SseEvent(String event, String data) {
    }
}
