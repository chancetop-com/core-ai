package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Request for SubscribeToTask.
 *
 * @author xander
 */
public class SubscribeToTaskRequest {
    @Property(name = "tenant")
    public String tenant;

    @Property(name = "id")
    public String id;
}
