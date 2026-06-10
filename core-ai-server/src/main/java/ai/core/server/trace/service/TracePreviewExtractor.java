package ai.core.server.trace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import ai.core.utils.JsonUtil;

/**
 * Builds a short list-row preview from the trace input so list responses can omit the full payload.
 *
 * @author Xander
 */
public final class TracePreviewExtractor {
    private static final int MAX_LENGTH = 200;

    public static String extract(String input) {
        if (input == null || input.isBlank()) return null;
        JsonNode root = parse(input);
        if (root != null) {
            var fromMessages = lastUserMessage(root);
            if (fromMessages != null) return compact(fromMessages);
            if (root.isTextual()) return compact(root.asText());
            var firstString = firstStringValue(root);
            if (firstString != null) return compact(firstString);
        }
        return compact(input);
    }

    private static JsonNode parse(String input) {
        try {
            return JsonUtil.OBJECT_MAPPER.readTree(input);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String lastUserMessage(JsonNode root) {
        var messages = root.get("messages");
        if (messages == null || !messages.isArray()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (!"user".equals(message.path("role").asText())) continue;
            var content = messageContent(message.get("content"));
            if (content != null && !content.isBlank()) return content;
        }
        return null;
    }

    private static String messageContent(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            var text = new StringBuilder();
            for (var part : content) {
                if (part.isTextual()) {
                    text.append(part.asText()).append(' ');
                } else if (part.path("text").isTextual()) {
                    text.append(part.path("text").asText()).append(' ');
                }
            }
            return text.toString();
        }
        return null;
    }

    private static String firstStringValue(JsonNode root) {
        if (!root.isObject()) return null;
        for (var entry : root.properties()) {
            if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                return entry.getValue().asText();
            }
        }
        return null;
    }

    private static String compact(String text) {
        var collapsed = text.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= MAX_LENGTH) return collapsed;
        return collapsed.substring(0, MAX_LENGTH) + "...";
    }

    private TracePreviewExtractor() {
    }
}
