package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Optional execution preferences for a send message request.
 *
 * @author xander
 */
public class SendMessageConfiguration {
    @Property(name = "acceptedOutputModes")
    public List<String> acceptedOutputModes;

    @Property(name = "taskPushNotificationConfig")
    public TaskPushNotificationConfig taskPushNotificationConfig;

    @Property(name = "historyLength")
    public Integer historyLength;

    @Property(name = "returnImmediately")
    public Boolean returnImmediately;
}
