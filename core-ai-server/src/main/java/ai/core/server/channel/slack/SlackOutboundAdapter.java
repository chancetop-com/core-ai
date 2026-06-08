package ai.core.server.channel.slack;

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
public class SlackOutboundAdapter implements ChannelOutboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlackOutboundAdapter.class);
    private static final String SLACK_API = "https://slack.com/api/chat.postMessage";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_TEXT_LENGTH = 3000; // Slack message limit safety margin

    private final HTTPClient httpClient = new PatchedHTTPClientBuilder()
            .timeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String type() {
        return "slack";
    }

    @Override
    public void sendText(String channelUserId, String conversationId, String text,
                          String threadId, Map<String, String> config) {
        var token = config.get("bot_token");
        if (token == null || token.isBlank()) {
            LOGGER.warn("slack bot_token not configured, cannot send message");
            return;
        }

        var body = buildMessageBody(conversationId, text, threadId);
        postMessage(token, body);
    }

    @Override
    public void sendApprovalRequest(String channelUserId, String conversationId,
                                     ToolApprovalRequestEvent event, String threadId,
                                     Map<String, String> config) {
        var token = config.get("bot_token");
        if (token == null || token.isBlank()) return;

        var text = "Approve tool `" + event.toolName + "`?\n" +
                   "Arguments: " + truncate(event.arguments, 150) + "\n" +
                   "Reply:\n" +
                   "`approve " + event.callId + "`\n" +
                   "`deny " + event.callId + "`";

        var body = buildMessageBody(conversationId, text, threadId);
        postMessage(token, body);
    }

    @Override
    public void sendTurnComplete(String channelUserId, String conversationId,
                                  TurnCompleteEvent event, String threadId,
                                  Map<String, String> config) {
        if (event.output != null && !event.output.isBlank()) {
            sendText(channelUserId, conversationId, event.output, threadId, config);
        }
    }

    private Map<String, Object> buildMessageBody(String channel, String text, String threadTs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("channel", channel);
        body.put("text", truncate(text, MAX_TEXT_LENGTH));
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        if (threadTs != null && !threadTs.isBlank()) {
            body.put("thread_ts", threadTs);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private void postMessage(String token, Map<String, Object> body) {
        try {
            var json = JSON.toJSON(body);
            var request = new HTTPRequest(HTTPMethod.POST, SLACK_API);
            request.contentType = ContentType.APPLICATION_JSON;
            request.headers.put("Authorization", "Bearer " + token);
            request.body(json.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

            var response = httpClient.execute(request);
            var responseBody = response.text();
            if (responseBody == null) {
                LOGGER.warn("slack API returned null response");
                return;
            }

            var result = (Map<String, Object>) JSON.fromJSON(Map.class, responseBody);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                var error = result.get("error");
                LOGGER.warn("slack API error: {}", error != null ? error : "unknown");
            }
        } catch (Exception e) {
            LOGGER.warn("failed to send slack message, channel={}", body.get("channel"), e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
