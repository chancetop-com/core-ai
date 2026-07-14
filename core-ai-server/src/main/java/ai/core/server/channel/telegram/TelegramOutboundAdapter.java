package ai.core.server.channel.telegram;

import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.server.channel.ChannelOutboundAdapter;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class TelegramOutboundAdapter implements ChannelOutboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramOutboundAdapter.class);
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_TEXT_LENGTH = 4000; // Telegram limit is 4096

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private final HTTPClient httpClient = new PatchedHTTPClientBuilder()
            .timeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public void sendText(String channelUserId, String conversationId, String text,
                          String threadId, Map<String, String> config) {
        var token = config.get("bot_token");
        if (token == null || token.isBlank()) {
            LOGGER.warn("telegram bot_token not configured");
            return;
        }

        var body = buildMessageBody(conversationId, text, threadId);
        sendMessage(token, body);
    }

    @Override
    public void sendApprovalRequest(String channelUserId, String conversationId,
                                     ToolApprovalRequestEvent event, String threadId,
                                     Map<String, String> config) {
        var token = config.get("bot_token");
        if (token == null || token.isBlank()) return;

        var text = "Approve tool `" + event.toolName + "`?\n"
                   + "Arguments: " + truncate(event.arguments, 150) + "\n"
                   + "Reply:\n"
                   + "`approve " + event.callId + "`\n"
                   + "`deny " + event.callId + "`";

        var body = buildMessageBody(conversationId, text, threadId);
        sendMessage(token, body);
    }

    @Override
    public void sendTurnComplete(String channelUserId, String conversationId,
                                  TurnCompleteEvent event, String threadId,
                                  Map<String, String> config) {
        if (event.output != null && !event.output.isBlank()) {
            sendText(channelUserId, conversationId, event.output, threadId, config);
        }
    }

    private Map<String, Object> buildMessageBody(String chatId, String text, String replyToMessageId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", truncate(text, MAX_TEXT_LENGTH));
        body.put("parse_mode", "Markdown");
        body.put("disable_web_page_preview", Boolean.TRUE);
        if (replyToMessageId != null && !replyToMessageId.isBlank()) {
            body.put("reply_to_message_id", Integer.valueOf(replyToMessageId));
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private void sendMessage(String token, Map<String, Object> body) {
        try {
            var url = String.format(TELEGRAM_API, token);
            var json = JSON.toJSON(body);
            var request = new HTTPRequest(HTTPMethod.POST, url);
            request.contentType = ContentType.APPLICATION_JSON;
            request.body(json.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

            var response = httpClient.execute(request);
            var responseBody = response.text();
            if (responseBody == null) return;

            var result = (Map<String, Object>) JSON.fromJSON(Map.class, responseBody);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                var description = result.get("description");
                LOGGER.warn("telegram API error: {}", description != null ? description : "unknown");
            }
        } catch (Exception e) {
            LOGGER.warn("failed to send telegram message, chatId={}", body.get("chat_id"), e);
        }
    }
}
