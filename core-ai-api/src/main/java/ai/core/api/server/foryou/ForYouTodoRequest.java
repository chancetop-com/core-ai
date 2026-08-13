package ai.core.api.server.foryou;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ForYouTodoRequest {
    @Property(name = "title")
    public String title;

    @Property(name = "description")
    public String description;

    @Property(name = "completed")
    public Boolean completed;

    @Property(name = "priority")
    public String priority;

    @Property(name = "due_date")
    public String dueDate;
}
