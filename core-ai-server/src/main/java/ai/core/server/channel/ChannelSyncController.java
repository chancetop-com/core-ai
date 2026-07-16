package ai.core.server.channel;

import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OpenAI-compatible synchronous endpoint for channel gateways (WeClaw, OpenClaw, etc.).
 * <p>
 * Receives an OpenAI chat completions request, dispatches to the agent system,
 * polls for the agent response, and returns it inline in OpenAI format.
 *
 * @author stephen
 */
public class ChannelSyncController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelSyncController.class);
    private static final long POLL_TIMEOUT_MS = 30 * 60 * 1000;
    private static final long POLL_INTERVAL_MS = 500;
    private final ConcurrentMap<String, String> sessionCache = new ConcurrentHashMap<>();

    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    OcgCallbackPool ocgCallbackPool;
    @Inject
    OcgConfigStore ocgConfigStore;

    @Override
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    public Response execute(Request request) {
        var payload = parseBody(request);
        var userText = extractUserText(payload);
        if (userText == null) throw new BadRequestException("no user message found in request");

        var channelId = request.pathParam("channelId");
        var callbackUrl = request.header("X-OCG-Callback").orElse(null);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            authenticateOcg(channelId);
        }
        var channel = loadChannel(channelId, callbackUrl);
        var agent = agentDefinitionService.getEntity(channel.agentId);
        if (agent == null) throw new NotFoundException("agent not found: " + channel.agentId);

        var userId = channel.userId != null ? channel.userId : channelId + ":" + UUID.randomUUID().toString().substring(0, 8);
        var userField = (String) payload.get("user");
        var asyncOcg = callbackUrl != null && !callbackUrl.isBlank();
        var isNewConversation = !asyncOcg && countUserMessages(payload) == 1 && (userField == null || !sessionCache.containsKey(userField));

        var sessionId = resolveSession(userField, isNewConversation, userId, channel, agent, channelId);
        chatMessageService.writeUserMessage(sessionId, userText);
        var command = SessionCommand.sendMessage(sessionId, userId, userText, null);
        commandPublisher.publish(command);

        if (asyncOcg) {
            int initialMessageCount = chatMessageService.history(sessionId).size();
            ocgCallbackPool.submit(sessionId, callbackUrl, channelId, initialMessageCount);
            return Response.text(JsonUtil.toJson(Map.of("status", "accepted"))).status(HTTPStatus.ACCEPTED);
        }

        var response = pollForResponse(sessionId);
        if (response == null) {
            throw new RuntimeException("agent did not respond within timeout");
        }
        return buildOpenAIResponse(sessionId, response);
    }

    private String resolveSession(String userField, boolean isNewConversation, String userId,
                                   ChannelConfigView channel, AgentDefinition agent, String channelId) {
        if (userField != null && !isNewConversation && sessionCache.containsKey(userField)) {
            var sessionId = sessionCache.get(userField);
            try {
                sessionManager.getSession(sessionId);
                LOGGER.info("sync dispatch (reused), channelId={}, sessionId={}, agentId={}, userField={}", channelId, sessionId, channel.agentId, userField);
                return sessionId;
            } catch (NotFoundException e) {
                sessionCache.remove(userField, sessionId);
            }
        }
        if (userField != null && isNewConversation) {
            sessionCache.remove(userField);
        }
        var config = new SessionConfig();
        config.channelType = resolveEffectiveChannelType(channel, userField);
        var result = sessionManager.createSessionFromAgent(agent, config, userId, "channel");
        var sessionId = result.sessionId();
        if (userField != null) {
            sessionCache.put(userField, sessionId);
        }
        LOGGER.info("sync dispatch (new), channelId={}, sessionId={}, agentId={}, user={}", channelId, sessionId, channel.agentId, userField);
        return sessionId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Request request) {
        var body = request.body();
        if (body.isEmpty()) throw new BadRequestException("body is required");
        var bodyStr = new String(body.get(), StandardCharsets.UTF_8);
        try {
            return (Map<String, Object>) JsonUtil.fromJson(Map.class, bodyStr);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "INVALID_JSON", e);
        }
    }

    private ChannelConfigView loadChannel(String channelId, String callbackUrl) {
        var channel = channelConfigStore.load(channelId);
        if (channel == null) throw new NotFoundException("channel not configured: " + channelId + ", create it in Channels page");
        if (callbackUrl != null && !callbackUrl.isBlank() && !"openclaw".equals(channel.channelType)) {
            throw new BadRequestException("OCG async callback is only supported for openclaw channels");
        }
        if (channel.agentId == null || channel.agentId.isBlank())
            throw new BadRequestException("channel " + channelId + " has no agent configured");
        return channel;
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
                    if (part instanceof Map<?, ?> partMap
                            && "text".equals(partMap.get("type"))
                            && partMap.get("text") instanceof String s
                            && !s.isBlank()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private String pollForResponse(String sessionId) {
        int initialCount = chatMessageService.history(sessionId).size();
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            var messages = chatMessageService.history(sessionId);
            for (int i = initialCount; i < messages.size(); i++) {
                var msg = messages.get(i);
                if ("agent".equals(msg.role) && msg.content != null && !msg.content.isBlank()) {
                    return msg.content;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void authenticateOcg(String channelId) {
        var config = ocgConfigStore.loadByChannelId(channelId);
        if (config == null || !Boolean.TRUE.equals(config.enabled)) throw new NotFoundException("OCG config not found for channel: " + channelId);
    }

    private String resolveEffectiveChannelType(ChannelConfigView channel, String userField) {
        if (!"openclaw".equals(channel.channelType)) return channel.channelType;
        if (userField == null || userField.isBlank()) return "openclaw";
        int colonIndex = userField.indexOf(':');
        return colonIndex > 0 ? userField.substring(0, colonIndex) : userField;
    }

    @SuppressWarnings("unchecked")
    private int countUserMessages(Map<String, Object> payload) {
        var messages = payload.get("messages");
        if (!(messages instanceof List<?> list)) return 0;
        int count = 0;
        for (var item : list) {
            if (item instanceof Map<?, ?> msg && "user".equals(msg.get("role"))) {
                count++;
            }
        }
        return count;
    }

    private Response buildOpenAIResponse(String sessionId, String response) {
        var choices = new ArrayList<Map<String, Object>>();
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", response);
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        choices.add(choice);

        var openAIResponse = new LinkedHashMap<String, Object>();
        openAIResponse.put("id", sessionId);
        openAIResponse.put("object", "chat.completion");
        openAIResponse.put("choices", choices);
        return Response.text(JsonUtil.toJson(openAIResponse));
    }
}
