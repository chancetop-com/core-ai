package ai.core.server.channel;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author stephen
 */
public class ChannelEventBridge implements AgentEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelEventBridge.class);

    private final ChannelOutboundAdapter outbound;
    private final String channelUserId;
    private final String conversationId;
    private final String threadId;
    private final Map<String, String> config;

    public ChannelEventBridge(ChannelOutboundAdapter outbound,
                              String channelUserId,
                              String conversationId,
                              String threadId,
                              Map<String, String> config) {
        this.outbound = outbound;
        this.channelUserId = channelUserId;
        this.conversationId = conversationId;
        this.threadId = threadId;
        this.config = config;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        try {
            outbound.sendText(channelUserId, conversationId, event.chunk, threadId, config);
        } catch (Exception e) {
            LOGGER.warn("failed to send text chunk to channel, userId={}, convId={}", channelUserId, conversationId, e);
        }
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        try {
            outbound.sendApprovalRequest(channelUserId, conversationId, event, threadId, config);
        } catch (Exception e) {
            LOGGER.warn("failed to send approval request to channel, userId={}, toolName={}",
                    channelUserId, event.toolName, e);
        }
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        try {
            var message = ChannelMessage.text(event.output);
            outbound.sendMessage(message, channelUserId, conversationId, threadId, config);
        } catch (Exception e) {
            LOGGER.warn("failed to send turn complete to channel, userId={}", channelUserId, e);
        }
    }

    @Override
    public void onError(ErrorEvent event) {
        try {
            outbound.sendText(channelUserId, conversationId,
                    "Error: " + truncate(event.message, 500), threadId, config);
        } catch (Exception e) {
            LOGGER.warn("failed to send error to channel, userId={}", channelUserId, e);
        }
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        // Only send tool results that the user should see (e.g., completed artifacts)
        if ("completed".equals(event.status) && event.result != null && !event.result.isBlank()) {
            try {
                outbound.sendText(channelUserId, conversationId,
                        "Tool `" + event.toolName + "` result:\n" + truncate(event.result, 1000),
                        threadId, config);
            } catch (Exception e) {
                LOGGER.warn("failed to send tool result to channel, userId={}, toolName={}",
                        channelUserId, event.toolName, e);
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated)";
    }
}
