package ai.core.server.channel;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class ChannelDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelDispatcher.class);
    private static final Pattern APPROVE_PATTERN = Pattern.compile("(?i)^(approve|allow|yes)\\s+(\\S+)");
    private static final Pattern DENY_PATTERN = Pattern.compile("(?i)^(deny|reject|no)\\s+(\\S+)");

    private final ConcurrentMap<String, String> conversationSessionMap = new ConcurrentHashMap<>();

    @Inject
    AgentSessionManager sessionManager;

    @Inject
    CommandPublisher commandPublisher;

    @Inject
    AgentDefinitionService agentDefinitionService;

    @Inject
    ChannelRegistry channelRegistry;

    /**
     * Dispatch an inbound event to its Agent session.
     */
    public void dispatch(ChannelConfigView channel, InboundEvent event) {
        var conversationKey = buildConversationKey(channel.channelId, event.conversationId);
        var sessionId = conversationSessionMap.get(conversationKey);

        if (sessionId == null || !sessionExists(sessionId)) {
            sessionId = createChannelSession(channel, event, conversationKey);
        }

        var userId = resolveUserId(channel, event);

        if (isToolDecision(event)) {
            var decision = parseDecision(event);
            if (decision != null) {
                LOGGER.info("dispatching tool decision, channelId={}, sessionId={}, callId={}, decision={}",
                        channel.channelId, sessionId, decision.callId, decision.decision);
                var command = SessionCommand.approveToolCall(sessionId, userId, decision.callId, decision.decision);
                commandPublisher.publish(command);
                return;
            }
        }

        LOGGER.info("dispatching channel message, channelId={}, type={}, sessionId={}",
                channel.channelId, channel.channelType, sessionId);
        var command = SessionCommand.sendMessage(sessionId, userId, event.messageText, null);
        commandPublisher.publish(command);

        attachChannelBridgeIfNeeded(sessionId, channel, event);
    }

    private boolean isToolDecision(InboundEvent event) {
        if ("tool_decision".equals(event.commandType)) return true;
        if (event.messageText == null) return false;
        return APPROVE_PATTERN.matcher(event.messageText).find()
                || DENY_PATTERN.matcher(event.messageText).find();
    }

    private ToolDecision parseDecision(InboundEvent event) {
        if (event.toolCallId != null && event.toolDecision != null && "tool_decision".equals(event.commandType)) {
            var decision = "approve".equalsIgnoreCase(event.toolDecision) ? ApprovalDecision.APPROVE : ApprovalDecision.DENY;
            return new ToolDecision(event.toolCallId, decision);
        }
        // Auto-detect from message text
        var text = event.messageText;
        if (text == null) return null;
        var approveMatch = APPROVE_PATTERN.matcher(text);
        if (approveMatch.find()) {
            return new ToolDecision(approveMatch.group(2), ApprovalDecision.APPROVE);
        }
        var denyMatch = DENY_PATTERN.matcher(text);
        if (denyMatch.find()) {
            return new ToolDecision(denyMatch.group(2), ApprovalDecision.DENY);
        }
        return null;
    }

    private String buildConversationKey(String channelId, String conversationId) {
        return channelId + ":" + conversationId;
    }

    private boolean sessionExists(String sessionId) {
        try {
            sessionManager.touchSession(sessionId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String createChannelSession(ChannelConfigView channel, InboundEvent event, String conversationKey) {
        var userId = resolveUserId(channel, event);
        var config = new SessionConfig();

        if (channel.agentId != null && !channel.agentId.isBlank()) {
            var definition = agentDefinitionService.getEntity(channel.agentId);
            if (definition == null) {
                throw new IllegalStateException("agent not found: " + channel.agentId);
            }
            var result = sessionManager.createSessionFromAgent(definition, config, userId, "channel");
            var sessionId = result.sessionId();

            conversationSessionMap.put(conversationKey, sessionId);
            LOGGER.info("created channel session, channelId={}, type={}, sessionId={}, userId={}, agent={}",
                    channel.channelId, channel.channelType, sessionId, userId, channel.agentId);
            return sessionId;
        }

        // Fallback: no agent configured, use default agent
        var sessionId = sessionManager.createSession(config, userId, "channel");
        conversationSessionMap.put(conversationKey, sessionId);
        LOGGER.info("created channel session (default agent), channelId={}, type={}, sessionId={}, userId={}",
                channel.channelId, channel.channelType, sessionId, userId);
        return sessionId;
    }

    private void attachChannelBridgeIfNeeded(String sessionId, ChannelConfigView channel, InboundEvent event) {
        try {
            var session = sessionManager.getSession(sessionId);
            channelRegistry.ensureChannelBridge(sessionId, session, channel, event);
        } catch (Exception e) {
            LOGGER.warn("failed to attach channel bridge, sessionId={}", sessionId, e);
        }
    }

    private String resolveUserId(ChannelConfigView channel, InboundEvent event) {
        if (channel.userId != null) return channel.userId;
        return "channel:" + channel.channelType + ":" + event.channelUserId;
    }

    private record ToolDecision(String callId, ApprovalDecision decision) { }
}
