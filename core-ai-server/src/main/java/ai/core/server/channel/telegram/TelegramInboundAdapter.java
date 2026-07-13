package ai.core.server.channel.telegram;

import ai.core.server.channel.ChannelInboundAdapter;
import ai.core.server.channel.InboundEvent;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class TelegramInboundAdapter implements ChannelInboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramInboundAdapter.class);
    private static final Pattern TOOL_DECISION_PATTERN = Pattern.compile("(?i)^\\s*(approve|allow|deny|reject)\\s+(\\S+)");

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public Optional<Response> handleChallenge(Request request, Map<String, String> config) {
        // Telegram doesn't have URL verification — it uses setWebhook with secret_token
        return Optional.empty();
    }

    @Override
    public void verify(Request request, Map<String, String> config) {
        var secretToken = config.get("secret_token");
        if (secretToken == null || secretToken.isBlank()) {
            // No secret token configured — skip verification (not recommended for production)
            return;
        }
        var header = request.header("X-Telegram-Bot-Api-Secret-Token");
        if (header.isEmpty() || !secretToken.equals(header.get())) {
            throw new ForbiddenException("invalid telegram secret token");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public InboundEvent parseEvent(Request request, Map<String, String> config) {
        var body = bodyAsString(request);
        if (body.isBlank()) return null;

        Map<String, Object> update;
        try {
            update = (Map<String, Object>) JSON.fromJSON(Map.class, body);
        } catch (Exception e) {
            LOGGER.warn("failed to parse telegram update", e);
            return null;
        }

        // Extract message from update
        var message = (Map<String, Object>) update.get("message");
        if (message == null) {
            message = (Map<String, Object>) update.get("edited_message");
        }
        if (message == null) return null;

        // Extract text
        var text = stringField(message, "text");
        if (text == null || text.isBlank()) return null;

        // Extract chat
        var chat = (Map<String, Object>) message.get("chat");
        if (chat == null) return null;

        var chatId = chat.get("id");
        if (chatId == null) return null;

        // Extract user
        var from = (Map<String, Object>) message.get("from");
        if (from == null) return null;
        var userId = from.get("id");
        if (userId == null) return null;

        // Ignore bot's own messages
        if (Boolean.TRUE.equals(from.get("is_bot"))) return null;

        var event = new InboundEvent();
        event.channelUserId = String.valueOf(userId);
        event.conversationId = String.valueOf(chatId);
        event.messageText = text.trim();

        // Extract reply context
        var replyTo = (Map<String, Object>) message.get("reply_to_message");
        if (replyTo != null) {
            event.threadId = String.valueOf(replyTo.get("message_id"));
        }

        // Detect tool approval intent
        detectToolDecision(event);

        return event;
    }

    private void detectToolDecision(InboundEvent event) {
        if (event.messageText == null) return;
        var matcher = TOOL_DECISION_PATTERN.matcher(event.messageText);
        if (matcher.find()) {
            event.commandType = "tool_decision";
            event.toolDecision = matcher.group(1).toLowerCase(Locale.ROOT);
            if ("allow".equals(event.toolDecision)) event.toolDecision = "approve";
            if ("reject".equals(event.toolDecision)) event.toolDecision = "deny";
            event.toolCallId = matcher.group(2);
        }
    }

    private String stringField(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private String bodyAsString(Request request) {
        var body = request.body();
        if (body.isEmpty()) return "";
        return new String(body.get(), StandardCharsets.UTF_8);
    }
}
