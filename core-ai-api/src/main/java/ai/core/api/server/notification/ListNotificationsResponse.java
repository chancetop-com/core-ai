package ai.core.api.server.notification;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ListNotificationsResponse {
    @NotNull
    @Property(name = "notifications")
    public List<NotificationView> notifications;

    @NotNull
    @Property(name = "total")
    public Long total;
}
