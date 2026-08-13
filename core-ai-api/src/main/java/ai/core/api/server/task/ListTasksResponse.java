package ai.core.api.server.task;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListTasksResponse {
    @Property(name = "tasks")
    public List<BackgroundTaskView> tasks;
}
