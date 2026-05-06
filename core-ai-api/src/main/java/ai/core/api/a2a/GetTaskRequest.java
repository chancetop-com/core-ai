package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Request for GetTask.
 *
 * @author xander
 */
public class GetTaskRequest {
    @Property(name = "tenant")
    public String tenant;

    @Property(name = "id")
    public String id;

    @Property(name = "historyLength")
    public Integer historyLength;
}
