package ai.core.api.server.task;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RunTaskRequest {
    @Property(name = "type")
    public String type;

    @Property(name = "date")
    public String date;
}
