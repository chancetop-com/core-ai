package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Request for CancelTask.
 *
 * @author xander
 */
public class CancelTaskRequest {
    @Property(name = "tenant")
    public String tenant;

    @Property(name = "id")
    public String id;

    @Property(name = "metadata")
    public Map<String, Object> metadata;
}
