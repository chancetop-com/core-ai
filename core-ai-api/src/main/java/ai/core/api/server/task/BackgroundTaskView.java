package ai.core.api.server.task;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class BackgroundTaskView {
    @Property(name = "id")
    public String id;

    @Property(name = "type")
    public String type;

    @Property(name = "status")
    public String status;

    @Property(name = "status_text")
    public String statusText;

    @Property(name = "claimed_by")
    public String claimedBy;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;

    @Property(name = "retry_count")
    public Integer retryCount;

    @Property(name = "logs")
    public List<String> logs;

    @Property(name = "task_state")
    public String taskState;
}
