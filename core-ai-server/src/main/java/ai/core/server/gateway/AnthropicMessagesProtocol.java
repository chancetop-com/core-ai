package ai.core.server.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates the Anthropic Messages protocol into the OpenAI chat completions protocol.
 * <p>
 * The gateway exposes the messages surface to Anthropic clients (Claude Code, Anthropic SDKs) and
 * serves it from chat providers by translating both the request and the response. Native anthropic
 * providers bypass this class: their body already speaks the protocol. The SSE translation lives in
 * {@link AnthropicMessagesStream}.
 */
final class AnthropicMessagesProtocol {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicMessagesProtocol.class);

    static Map<String, Object> toChatRequest(Map<String, Object> body, String upstreamModel) {
        var chat = new LinkedHashMap<String, Object>();
        chat.put("model", upstreamModel);
        var messages = new ArrayList<Object>();
        var system = systemText(body.get("system"));
        if (hasText(system)) messages.add(Map.of("role", "system", "content", system));
        messages.addAll(toChatMessages(body.get("messages")));
        if (!messages.isEmpty()) chat.put("messages", messages);
        putIfPresent(chat, "temperature", body.get("temperature"));
        putIfPresent(chat, "top_p", body.get("top_p"));
        if (body.get("max_tokens") instanceof Number maxTokens && maxTokens.longValue() > 0) {
            chat.put("max_tokens", maxTokens);
        }
        if (body.get("stop_sequences") instanceof List<?> stopSequences && !stopSequences.isEmpty()) {
            chat.put("stop", stopSequences);
        }
        var tools = toChatTools(body.get("tools"));
        if (tools != null) chat.put("tools", tools);
        var toolChoice = toChatToolChoice(body.get("tool_choice"));
        if (toolChoice != null) chat.put("tool_choice", toolChoice);
        if (body.get("stream") instanceof Boolean stream) chat.put("stream", stream);
        if (body.get("metadata") instanceof Map<?, ?> metadata && hasText(string(metadata.get("user_id")))) {
            chat.put("user", string(metadata.get("user_id")));
        }
        return chat;
    }

    static Map<String, Object> toMessageResponse(Map<String, Object> response, String requestedModel) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", hasText(string(response.get("id"))) ? string(response.get("id")) : newId("msg_"));
        result.put("type", "message");
        result.put("role", "assistant");
        result.put("model", requestedModel);

        var content = new ArrayList<Object>();
        String finishReason = null;
        if (response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice) {
            finishReason = string(choice.get("finish_reason"));
            var message = choice.get("message") instanceof Map<?, ?> map ? map : Map.of();
            addTextBlock(content, string(message.get("content")));
            addToolUseBlocks(content, message.get("tool_calls"));
        }
        if (content.isEmpty()) content.add(Map.of("type", "text", "text", ""));
        result.put("content", content);
        result.put("stop_reason", anthropicStopReason(finishReason, content));
        result.put("stop_sequence", null);
        result.put("usage", anthropicUsage(response.get("usage")));
        return result;
    }

    static Map<String, Object> toErrorResponse(int statusCode, Map<String, Object> upstream) {
        var error = new LinkedHashMap<String, Object>();
        error.put("type", errorType(statusCode, upstream));
        error.put("message", errorMessage(statusCode, upstream));
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "error");
        body.put("error", error);
        return body;
    }

    private static String errorMessage(int statusCode, Map<String, Object> upstream) {
        if (upstream == null) return "upstream returned HTTP " + statusCode;
        if (upstream.get("error") instanceof Map<?, ?> error && hasText(string(error.get("message")))) {
            return string(error.get("message"));
        }
        var message = string(upstream.get("message"));
        return hasText(message) ? message : "upstream returned HTTP " + statusCode;
    }

    private static String errorType(int statusCode, Map<String, Object> upstream) {
        if (upstream != null && upstream.get("error") instanceof Map<?, ?> error
                && isAnthropicErrorType(string(error.get("type")))) {
            return string(error.get("type"));
        }
        return anthropicErrorType(statusCode);
    }

    private static void addTextBlock(List<Object> content, String text) {
        if (hasText(text)) content.add(Map.of("type", "text", "text", text));
    }

    private static void addToolUseBlocks(List<Object> content, Object toolCalls) {
        if (!(toolCalls instanceof List<?> calls)) return;
        for (var toolCall : calls) {
            if (!(toolCall instanceof Map<?, ?> call)) continue;
            var function = call.get("function") instanceof Map<?, ?> map ? map : Map.of();
            var block = new LinkedHashMap<String, Object>();
            block.put("type", "tool_use");
            block.put("id", hasText(string(call.get("id"))) ? string(call.get("id")) : newId("toolu_"));
            block.put("name", hasText(string(function.get("name"))) ? string(function.get("name")) : "tool");
            block.put("input", parseToolInput(string(function.get("arguments"))));
            content.add(block);
        }
    }

    private static List<Object> toChatMessages(Object value) {
        var result = new ArrayList<Object>();
        if (!(value instanceof List<?> messages)) return result;
        for (var item : messages) {
            if (!(item instanceof Map<?, ?> message)) continue;
            if ("assistant".equals(string(message.get("role")))) {
                appendChatAssistantMessage(result, message);
            } else {
                appendChatUserMessages(result, message);
            }
        }
        return result;
    }

    private static void appendChatAssistantMessage(List<Object> result, Map<?, ?> message) {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<Object>();
        if (message.get("content") instanceof List<?> blocks) {
            for (var block : blocks) appendAssistantBlock(text, toolCalls, block);
        } else if (message.get("content") instanceof String content) {
            text.append(content);
        }
        var chatMessage = new LinkedHashMap<String, Object>();
        chatMessage.put("role", "assistant");
        chatMessage.put("content", text.toString());
        if (!toolCalls.isEmpty()) chatMessage.put("tool_calls", toolCalls);
        result.add(chatMessage);
    }

    private static void appendAssistantBlock(StringBuilder text, List<Object> toolCalls, Object block) {
        if (!(block instanceof Map<?, ?> blockMap)) return;
        var type = string(blockMap.get("type"));
        if ("text".equals(type) && hasText(string(blockMap.get("text")))) {
            appendText(text, string(blockMap.get("text")));
            return;
        }
        if ("tool_use".equals(type)) addToolCall(toolCalls, blockMap);
    }

    private static void addToolCall(List<Object> toolCalls, Map<?, ?> block) {
        var function = new LinkedHashMap<String, Object>();
        function.put("name", string(block.get("name")));
        function.put("arguments", json(block.get("input") == null ? Map.of() : block.get("input")));
        var call = new LinkedHashMap<String, Object>();
        call.put("id", string(block.get("id")));
        call.put("type", "function");
        call.put("function", function);
        toolCalls.add(call);
    }

    private static void appendText(StringBuilder text, String value) {
        if (text.length() > 0) text.append('\n');
        text.append(value);
    }

    private static void appendChatUserMessages(List<Object> result, Map<?, ?> message) {
        var parts = new ArrayList<Object>();
        if (message.get("content") instanceof List<?> blocks) {
            for (var block : blocks) appendUserBlock(result, parts, block);
        } else if (message.get("content") instanceof String content && hasText(content)) {
            parts.add(Map.of("type", "text", "text", content));
        }
        flushUserParts(result, parts);
    }

    private static void appendUserBlock(List<Object> result, List<Object> parts, Object block) {
        if (!(block instanceof Map<?, ?> blockMap)) return;
        var type = string(blockMap.get("type"));
        if ("tool_result".equals(type)) {
            flushUserParts(result, parts);
            addToolResultMessage(result, blockMap);
            return;
        }
        if ("text".equals(type) && hasText(string(blockMap.get("text")))) {
            parts.add(Map.of("type", "text", "text", string(blockMap.get("text"))));
            return;
        }
        if ("image".equals(type)) {
            addImagePart(parts, blockMap);
            return;
        }
        if ("document".equals(type) || "file".equals(type)) {
            parts.add(Map.of("type", "text", "text", "[document omitted]"));
        }
    }

    private static void addToolResultMessage(List<Object> result, Map<?, ?> block) {
        var toolMessage = new LinkedHashMap<String, Object>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", string(block.get("tool_use_id")));
        toolMessage.put("content", toolResultText(block));
        result.add(toolMessage);
    }

    private static void addImagePart(List<Object> parts, Map<?, ?> block) {
        var url = imageUrl(block.get("source"));
        if (url != null) parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
    }

    private static void flushUserParts(List<Object> result, List<Object> parts) {
        if (parts.isEmpty()) return;
        var texts = new ArrayList<String>();
        var onlyText = true;
        for (var part : parts) {
            if (!(part instanceof Map<?, ?> map)) continue;
            if ("text".equals(string(map.get("type"))) && map.get("text") instanceof String text) {
                texts.add(text);
            } else {
                onlyText = false;
            }
        }
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", onlyText ? String.join("\n", texts) : new ArrayList<>(parts));
        result.add(message);
        parts.clear();
    }

    static String toolResultText(Map<?, ?> block) {
        var content = block.get("content");
        if (content instanceof String text) return text;
        if (!(content instanceof List<?> blocks)) return "";
        var text = new StringBuilder();
        for (var item : blocks) {
            if (!(item instanceof Map<?, ?> blockMap)) continue;
            var type = string(blockMap.get("type"));
            if ("text".equals(type) && hasText(string(blockMap.get("text")))) {
                if (!text.isEmpty()) text.append('\n');
                text.append(string(blockMap.get("text")));
            } else if ("image".equals(type)) {
                text.append("[image]");
            }
        }
        return text.toString();
    }

    private static String imageUrl(Object source) {
        if (!(source instanceof Map<?, ?> sourceMap)) return null;
        var type = string(sourceMap.get("type"));
        if ("base64".equals(type)) {
            var mediaType = hasText(string(sourceMap.get("media_type"))) ? string(sourceMap.get("media_type")) : "image/png";
            var data = string(sourceMap.get("data"));
            return hasText(data) ? "data:" + mediaType + ";base64," + data : null;
        }
        if ("url".equals(type)) return string(sourceMap.get("url"));
        return null;
    }

    static String systemText(Object system) {
        if (system instanceof String text) return text;
        if (!(system instanceof List<?> blocks)) return null;
        var text = new StringBuilder();
        for (var block : blocks) {
            if (!(block instanceof Map<?, ?> blockMap) || !"text".equals(string(blockMap.get("type")))) continue;
            var value = string(blockMap.get("text"));
            if (!hasText(value)) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(value);
        }
        return text.toString();
    }

    private static List<Object> toChatTools(Object value) {
        if (!(value instanceof List<?> tools) || tools.isEmpty()) return null;
        var result = new ArrayList<Object>();
        for (var tool : tools) {
            if (!(tool instanceof Map<?, ?> toolMap) || !hasText(string(toolMap.get("name")))) continue;
            var function = new LinkedHashMap<String, Object>();
            function.put("name", string(toolMap.get("name")));
            if (hasText(string(toolMap.get("description")))) function.put("description", string(toolMap.get("description")));
            function.put("parameters", toolMap.get("input_schema") instanceof Map<?, ?> schema ? schema
                    : Map.of("type", "object", "properties", Map.of()));
            var chatTool = new LinkedHashMap<String, Object>();
            chatTool.put("type", "function");
            chatTool.put("function", function);
            result.add(chatTool);
        }
        return result.isEmpty() ? null : result;
    }

    private static Object toChatToolChoice(Object value) {
        if (!(value instanceof Map<?, ?> choice)) return null;
        return switch (string(choice.get("type"))) {
            case "auto" -> "auto";
            case "any" -> "required";
            case "none" -> "none";
            case "tool" -> hasText(string(choice.get("name")))
                    ? Map.of("type", "function", "function", Map.of("name", string(choice.get("name")))) : null;
            default -> null;
        };
    }

    private static Map<String, Object> anthropicUsage(Object value) {
        var usage = value instanceof Map<?, ?> map ? map : Map.of();
        long input = number(usage.get("prompt_tokens"), number(usage.get("input_tokens"), 0));
        long output = number(usage.get("completion_tokens"), number(usage.get("output_tokens"), 0));
        var result = new LinkedHashMap<String, Object>();
        result.put("input_tokens", input);
        result.put("output_tokens", output);
        result.put("cache_read_input_tokens", cachedTokens(usage));
        result.put("cache_creation_input_tokens", 0);
        return result;
    }

    private static long cachedTokens(Map<?, ?> usage) {
        var cached = usage.get("cache_read_input_tokens");
        if (cached == null) cached = usage.get("cached_tokens");
        if (cached == null && usage.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            cached = details.get("cached_tokens");
        }
        if (cached == null && usage.get("input_tokens_details") instanceof Map<?, ?> details) {
            cached = details.get("cached_tokens");
        }
        return number(cached, 0);
    }

    private static Map<String, Object> parseToolInput(String arguments) {
        if (!hasText(arguments)) return Map.of();
        try {
            return GatewayJson.MAPPER.readValue(arguments, GatewaySupport.MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.debug("tool call arguments are not a JSON object", e);
            return Map.of();
        }
    }

    static String anthropicStopReason(String finishReason, List<Object> content) {
        if ("length".equals(finishReason)) return "max_tokens";
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) return "tool_use";
        for (var block : content) {
            if (block instanceof Map<?, ?> map && "tool_use".equals(string(map.get("type")))) return "tool_use";
        }
        return "end_turn";
    }

    private static String anthropicErrorType(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> statusCode >= 500 ? "api_error" : "invalid_request_error";
        };
    }

    private static boolean isAnthropicErrorType(String type) {
        return switch (type == null ? "" : type) {
            case "invalid_request_error", "authentication_error", "permission_error", "not_found_error",
                 "request_too_large", "rate_limit_error", "api_error", "overloaded_error" -> true;
            default -> false;
        };
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    static String string(Object value) {
        return value instanceof String string ? string : null;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static long number(Object value, long defaultValue) {
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    static String json(Object value) {
        try {
            return GatewayJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize gateway protocol JSON", e);
        }
    }

    static Map<String, Object> parse(String data) {
        try {
            return GatewayJson.MAPPER.readValue(data, GatewaySupport.MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.debug("stream chunk is not a JSON payload", e);
            return null;
        }
    }

    static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private AnthropicMessagesProtocol() {
    }
}

