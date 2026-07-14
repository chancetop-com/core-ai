package ai.core.server.channel.weclaw;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.server.channel.ChannelMessage;
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
public class WeClawOutboundAdapter implements ChannelOutboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeClawOutboundAdapter.class);
    private static final String DEFAULT_API_BASE = "http://localhost:18011";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HTTPClient httpClient = new PatchedHTTPClientBuilder()
            .timeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String type() {
        return "weclaw";
    }

    /**
     * Send a text-only message — delegates to {@link #sendMessage} for unified handling.
     */
    @Override
    public void sendText(String channelUserId, String conversationId, String text,
                          String threadId, Map<String, String> config) {
        sendMessage(ChannelMessage.text(text), channelUserId, conversationId, threadId, config);
    }

    /**
     * Send a rich message with optional text and media.
     *
     * Maps {@link ChannelMessage} fields to WeClaw's {@code /api/send} payload.
     * If both text and media_url are present, WeClaw delivers them as a combined message.
     */
    @Override
    public void sendMessage(ChannelMessage message, String channelUserId, String conversationId,
                             String threadId, Map<String, String> config) {
        var apiBase = config.getOrDefault("weclaw_api_base", DEFAULT_API_BASE);
        var url = apiBase + "/api/send";

        var body = new LinkedHashMap<String, Object>();
        body.put("to", channelUserId);
        if (message.text != null && !message.text.isBlank()) {
            body.put("text", message.text);
        }
        if (message.mediaUrl != null && !message.mediaUrl.isBlank()) {
            body.put("media_url", message.mediaUrl);
        }

        send(apiBase, url, body);
    }

    /**
     * Raw send for WeClaw custom operations.
     *
     * Supported actions:
     * <pre>
     * "send" - sends a custom payload to WeClaw's /api/send (maps payload directly)
     * </pre>
     */
    @Override
    public void sendRaw(String action, Map<String, Object> payload,
                         String channelUserId, String conversationId,
                         Map<String, String> config) {
        if ("send".equals(action)) {
            var apiBase = config.getOrDefault("weclaw_api_base", DEFAULT_API_BASE);
            if (!payload.containsKey("to")) {
                payload.put("to", channelUserId);
            }
            send(apiBase, apiBase + "/api/send", payload);
            return;
        }
        LOGGER.warn("weclaw unknown raw action: {}", action);
    }

    @SuppressWarnings("unchecked")
    private void send(String apiBase, String url, Map<String, Object> body) {
        try {
            var json = JSON.toJSON(body);
            var request = new HTTPRequest(HTTPMethod.POST, url);
            request.contentType = ContentType.APPLICATION_JSON;
            request.body(json.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

            var response = httpClient.execute(request);
            var responseBody = response.text();
            if (responseBody == null) return;

            var result = (Map<String, Object>) JSON.fromJSON(Map.class, responseBody);
            var error = result.get("error");
            if (error != null) {
                LOGGER.warn("weclaw API error: {}", error);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to send weclaw message, to={}, apiBase={}", body.get("to"), apiBase, e);
        }
    }
}
