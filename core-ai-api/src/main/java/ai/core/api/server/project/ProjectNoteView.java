package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectNoteView {
    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "content")
    public String content;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "created_by")
    public String createdBy;
}
