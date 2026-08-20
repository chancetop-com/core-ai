package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectActionItemView {
    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "id")
    public String id;

    @Property(name = "title")
    public String title;

    @Property(name = "status")
    public String status;

    @Property(name = "note")
    public String note;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Property(name = "updated_by")
    public String updatedBy;
}
