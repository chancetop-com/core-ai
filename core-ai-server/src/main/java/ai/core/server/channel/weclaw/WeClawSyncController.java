package ai.core.server.channel.weclaw;

import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous OpenAI-compatible endpoint for WeClaw.
 * <p>
 * WeClaw's HTTP agent sends an OpenAI chat completions request and expects
 * the response inline. Since the agent system is async (pub/sub via Redis),
 * this controller polls {@link ChatMessageService#history(String)} until the
 * agent responds, then returns the result in OpenAI format.
 *
 * @author stephen
 */
public class WeClawSyncController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeClawSyncController.class);
    private static final long POLL_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 500;

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

    @Override
    @SuppressWarnings("unchecked")
    public Response execute(Request request) {
        var body = request.body();
        if (body.isEmpty()) throw new BadRequestException("body is required");
        var bodyStr = new String(body.get(), StandardCharsets.UTF_8);

        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JsonUtil.fromJson(Map.class, bodyStr);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage());
        }

        var userText = extractUserText(payload);
        if (userText == null) throw new BadRequestException("no user message found in request");

        var channel = channelConfigStore.load("weclaw");
        if (channel == null) throw new NotFoundException("weclaw channel not configured, create it in Triggers > Channels");
        if (channel.agentId == null || channel.agentId.isBlank())
            throw new BadRequestException("weclaw channel has no agent configured");

        var agent = agentDefinitionService.getEntity(channel.agentId);
        if (agent == null) throw new NotFoundException("agent not found: " + channel.agentId);

        var userId = "weclaw:" + UUID.randomUUID().toString().substring(0, 8);

        var config = new SessionConfig();
        var result = sessionManager.createSessionFromAgent(agent, config, userId, "weclaw");
        var sessionId = result.sessionId();

        chatMessageService.registerSession(sessionId, userId, channel.agentId);
        chatMessageService.writeUserMessage(sessionId, userText);

        var command = SessionCommand.sendMessage(sessionId, userId, userText, null);
        commandPublisher.publish(command);

        LOGGER.info("weclaw sync dispatch, sessionId={}, agentId={}, textLen={}", sessionId, channel.agentId, userText.length());

        var response = pollForResponse(sessionId);
        if (response == null) {
            throw new RuntimeException("agent did not respond within timeout");
        }

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
}
