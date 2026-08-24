package ai.core.server.domain;

/**
 * Delivery target for a run's output — the messaging channel (by id) and the
 * recipient within it (Slack user/channel id, Telegram chat id). Built from the
 * schedule's channel configuration by {@link AgentSchedule#channelTarget()}.
 *
 * @author stephen
 */
public record ChannelTarget(String id, String recipientId) {
}
