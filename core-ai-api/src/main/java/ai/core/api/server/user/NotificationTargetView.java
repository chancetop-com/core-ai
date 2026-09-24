package ai.core.api.server.user;

import core.framework.api.json.Property;

/**
 * An address the platform has seen this user write from — for QQ the {@code qqbot:c2c:<openid>}
 * carried by their message — so a notification target can be picked instead of typed.
 *
 * @author stephen
 */
public class NotificationTargetView {
    @Property(name = "channel_id")
    public String channelId;

    @Property(name = "channel_type")
    public String channelType;

    @Property(name = "recipient")
    public String recipient;
}
