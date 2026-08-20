package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectSubjectStatusView {
    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "phase")
    public String phase;

    @Property(name = "summary")
    public String summary;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Property(name = "updated_by")
    public String updatedBy;
}
