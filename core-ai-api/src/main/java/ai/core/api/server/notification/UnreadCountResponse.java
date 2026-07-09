package ai.core.api.server.notification;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class UnreadCountResponse {
    @NotNull
    @Property(name = "unread_count")
    public Long unreadCount;
}
