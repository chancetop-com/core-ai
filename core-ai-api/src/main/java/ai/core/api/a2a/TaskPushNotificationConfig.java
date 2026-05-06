package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Push notification configuration bound to a task.
 *
 * @author xander
 */
public class TaskPushNotificationConfig {
    @Property(name = "tenant")
    public String tenant;

    @Property(name = "id")
    public String id;

    @Property(name = "taskId")
    public String taskId;

    @Property(name = "url")
    public String url;

    @Property(name = "token")
    public String token;

    @Property(name = "authentication")
    public AuthenticationInfo authentication;
}
