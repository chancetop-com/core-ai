package ai.core.api.server.task;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RunTaskResponse {
    @Property(name = "task_accepted")
    public Boolean taskAccepted;

    @Property(name = "task_id")
    public String taskId;
}
