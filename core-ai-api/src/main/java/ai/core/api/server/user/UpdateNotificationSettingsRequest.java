package ai.core.api.server.user;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateNotificationSettingsRequest {
    @Property(name = "channel_id")
    public String channelId;

    @Property(name = "recipient")
    public String recipient;

    @Property(name = "min_minutes")
    public Integer minMinutes;
}
