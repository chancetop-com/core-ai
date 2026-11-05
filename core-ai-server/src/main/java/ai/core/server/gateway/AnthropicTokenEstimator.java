package ai.core.server.gateway;

import ai.core.document.Tokenizer;

import java.util.List;
import java.util.Map;

/**
 * Approximate token count of a Messages request, used by /messages/count_tokens when the route is a
 * chat provider (OpenAI has no token-counting endpoint). Images use the same fixed cost as the
 * agent's own context accounting; the number drives a context meter, not billing.
 */
final class AnthropicTokenEstimator {
    static int estimate(Map<String, Object> body) {
        var tokens = Tokenizer.tokenCount(AnthropicMessagesProtocol.systemText(body.get("system")));
        if (body.get("messages") instanceof List<?> messages) {
            for (var item : messages) {
                if (item instanceof Map<?, ?> message) tokens += messageTokens(message);
            }
        }
        tokens += toolsTokens(body.get("tools"));
        return Math.max(tokens, 1);
    }

    private static int messageTokens(Map<?, ?> message) {
        int tokens = 4;
        var content = message.get("content");
        if (content instanceof String text) return tokens + Tokenizer.tokenCount(text);
        if (!(content instanceof List<?> blocks)) return tokens;
        for (var block : blocks) {
            if (block instanceof Map<?, ?> blockMap) tokens += blockTokens(blockMap);
        }
        return tokens;
    }

    private static int blockTokens(Map<?, ?> block) {
        return switch (AnthropicMessagesProtocol.string(block.get("type"))) {
            case "text" -> Tokenizer.tokenCount(AnthropicMessagesProtocol.string(block.get("text")));
            case "thinking" -> Tokenizer.tokenCount(AnthropicMessagesProtocol.string(block.get("thinking")));
            case "tool_use" -> Tokenizer.tokenCount(AnthropicMessagesProtocol.string(block.get("name")))
                    + Tokenizer.tokenCount(AnthropicMessagesProtocol.json(block.get("input") == null ? Map.of() : block.get("input")));
            case "tool_result" -> Tokenizer.tokenCount(AnthropicMessagesProtocol.toolResultText(block)) + 8;
            case "image" -> 1000;
            default -> 0;
        };
    }

    private static int toolsTokens(Object value) {
        if (!(value instanceof List<?> tools)) return 0;
        int tokens = 0;
        for (var tool : tools) {
            if (!(tool instanceof Map<?, ?> toolMap)) continue;
            tokens += Tokenizer.tokenCount(AnthropicMessagesProtocol.string(toolMap.get("name")));
            tokens += Tokenizer.tokenCount(AnthropicMessagesProtocol.string(toolMap.get("description")));
            tokens += Tokenizer.tokenCount(AnthropicMessagesProtocol.json(
                    toolMap.get("input_schema") == null ? Map.of() : toolMap.get("input_schema")));
        }
        return tokens;
    }

    private AnthropicTokenEstimator() {
    }
}
