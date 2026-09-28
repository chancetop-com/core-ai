package ai.core.api.server.user;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class NotificationChannelView {
    @Property(name = "channel_id")
    public String channelId;

    @Property(name = "channel_type")
    public String channelType;

    /** True when the channel is platform-wide (no owning user): messages land in a shared place. */
    @Property(name = "shared")
    public Boolean shared;
}
