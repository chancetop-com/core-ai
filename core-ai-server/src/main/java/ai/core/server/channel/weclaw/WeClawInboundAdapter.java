package ai.core.server.channel.weclaw;

import ai.core.server.channel.ChannelInboundAdapter;
import ai.core.server.channel.InboundEvent;
import ai.core.utils.JsonUtil;
import core.framework.web.Request;
import core.framework.web.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class WeClawInboundAdapter implements ChannelInboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeClawInboundAdapter.class);

    @Override
    public String type() {
        return "weclaw";
    }

    @Override
    public Optional<Response> handleChallenge(Request request, Map<String, String> config) {
        return Optional.empty();
    }

    @Override
    public void verify(Request request, Map<String, String> config) {
        // No secret verification needed — WeClaw runs locally on the same machine.
    }

    @Override
    public InboundEvent parseEvent(Request request, Map<String, String> config) {
        var bodyOpt = request.body();
        if (bodyOpt.isEmpty()) return null;
        var bodyString = new String(bodyOpt.get(), StandardCharsets.UTF_8);
        try {
            // Parse as raw map — WeClaw sends content as string, not array.
            // JsonUtil.fromJson(CompletionRequest.class, ...) would fail because
            // Message.content is List<Content> and @JsonDeserialize isn't picked up.
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) JsonUtil.fromJson(Map.class, bodyString);

            var text = extractUserText(payload);
            if (text == null) {
                LOGGER.debug("weclaw: no user message text found");
                return null;
            }

            var userId = config.get("weclaw_user_id");
            if (userId == null || userId.isBlank()) {
                LOGGER.warn("weclaw: weclaw_user_id not configured in channel config, event dropped");
                return null;
            }

            var event = new InboundEvent();
            event.channelType = "weclaw";
            event.channelUserId = userId;
            event.conversationId = userId;
            event.messageText = text;
            event.commandType = "message";

            LOGGER.info("weclaw inbound event, userId={}, textLen={}", userId, text.length());
            return event;
        } catch (Exception e) {
            LOGGER.warn("failed to parse weclaw webhook body", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractUserText(Map<String, Object> payload) {
        var messages = payload.get("messages");
        if (!(messages instanceof List<?> list)) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!(list.get(i) instanceof Map<?, ?> msg)) continue;
            var role = msg.get("role");
            if (!"user".equals(role)) continue;
            var content = msg.get("content");
            if (content instanceof String text && !text.isBlank()) return text;
            if (content instanceof List<?> parts) {
                for (var part : parts) {
                    if (part instanceof Map<?, ?> partMap && "text".equals(partMap.get("type"))) {
                        var text = (String) partMap.get("text");
                        if (text != null && !text.isBlank()) return text;
                    }
                }
            }
        }
        return null;
    }
}
