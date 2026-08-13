package ai.core.api.server.foryou;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ForYouTodoView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;

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

    @Property(name = "created_at")
    public String createdAt;

    @Property(name = "updated_at")
    public String updatedAt;
}
