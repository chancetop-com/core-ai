package ai.core.server.channel;

import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;

import java.util.Map;

/**
 * @author stephen
 */
public interface ChannelOutboundAdapter {

    /** Channel type identifier matching ChannelConfigView.channelType. */
    String type();

    /**
     * Send a text chunk to the user. Implementations may buffer chunks to reduce API calls,
     * or send each chunk as a message update for real-time streaming feel.
     *
     * @param channelUserId    platform-specific user id
     * @param conversationId   platform-specific conversation/channel id
     * @param text             the text to send
     * @param threadId         parent thread id (null if no thread context)
     * @param config           channel runtime config (tokens, secrets)
     */
    void sendText(String channelUserId, String conversationId, String text, String threadId, Map<String, String> config);

    /**
     * Send a rich outbound message with optional text, media, and custom parameters.
     *
     * This is the preferred method for delivering completed turn output to channels
     * that support rich media (images, files, videos). Simple channels can rely on
     * the default fallback which delegates to {@link #sendText}.
     *
     * Channels that need custom send operations (e.g. WeClaw proactive send to a
     * specific user, or sending files) should override this method and handle the
     * full {@link ChannelMessage} payload.
     */
    default void sendMessage(ChannelMessage message, String channelUserId, String conversationId,
                              String threadId, Map<String, String> config) {
        if (message.text != null && !message.text.isBlank()) {
            sendText(channelUserId, conversationId, message.text, threadId, config);
        }
    }

    /**
     * Channel-aware variant of {@link #sendMessage}: also receives the core-ai channel id.
     *
     * Gateway-backed channels (e.g. {@code openclaw}) route through a per-channel sandbox and
     * therefore cannot resolve their delivery endpoint from the recipient alone — the channel
     * id is what maps to the gateway config. Simple channels ignore this and keep using the
     * 5-argument overload (the default implementation delegates to it).
     *
     * @param channelId        core-ai channel id ({@code channels} collection key)
     * @param channelUserId    platform-specific user id
     * @param conversationId   platform-specific conversation/channel id
     * @param threadId         parent thread id (null if no thread context)
     * @param config           channel runtime config (tokens, secrets)
     */
    default void sendMessage(ChannelMessage message, String channelId, String channelUserId,
                              String conversationId, String threadId, Map<String, String> config) {
        sendMessage(message, channelUserId, conversationId, threadId, config);
    }

    /**
     * Send a raw payload to the channel — escape hatch for channel-specific operations
     * that don't fit the standard message model. The payload shape is defined by the
     * channel adapter implementation and is NOT standardized across channels.
     *
     * Use this for custom actions like "create_channel", "add_reaction", "kick_user",
     * or any other platform-specific operation.
     */
    default void sendRaw(String action, Map<String, Object> payload,
                          String channelUserId, String conversationId,
                          Map<String, String> config) {
        // no-op by default — override for channels that support custom operations
    }

    /**
     * Notify the channel user that a tool requires approval.
     * Default sends a text message describing the approval request.
     */
    default void sendApprovalRequest(String channelUserId, String conversationId,
                                      ToolApprovalRequestEvent event, String threadId,
                                      Map<String, String> config) {
        var text = "Approve tool `" + event.toolName + "`?\n"
                   + "Arguments: " + truncate(event.arguments, 200) + "\n"
                   + "Reply `approve " + event.callId + "` or `deny " + event.callId + "`.";
        sendText(channelUserId, conversationId, text, threadId, config);
    }

    /** Signal that the agent turn has completed. */
    default void sendTurnComplete(String channelUserId, String conversationId,
                                   TurnCompleteEvent event, String threadId,
                                   Map<String, String> config) {
        var message = ChannelMessage.text(event.output);
        sendMessage(message, channelUserId, conversationId, threadId, config);
    }

    /** Notify about a tool execution start. Default no-op — override for rich channels. */
    default void sendToolStart(String channelUserId, String conversationId,
                                ToolStartEvent event, String threadId,
                                Map<String, String> config) {
        // no-op by default
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
