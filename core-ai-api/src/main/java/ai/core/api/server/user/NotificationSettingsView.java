package ai.core.api.server.user;

import core.framework.api.json.Property;

import java.util.List;

/**
 * A user's own session-completion notification target: where to be told that a chat session
 * finished, and how long a turn must run before it is worth a message.
 *
 * @author stephen
 */
public class NotificationSettingsView {
    @Property(name = "channel_id")
    public String channelId;

    @Property(name = "recipient")
    public String recipient;

    @Property(name = "min_minutes")
    public Integer minMinutes;

    /** Channels this user may deliver to; empty when none is configured. */
    @Property(name = "channels")
    public List<NotificationChannelView> channels;
}
