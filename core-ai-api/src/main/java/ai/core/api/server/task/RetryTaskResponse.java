package ai.core.api.server.task;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RetryTaskResponse {
    @Property(name = "retry_accepted")
    public Boolean retryAccepted;

    @Property(name = "task_id")
    public String taskId;
}
