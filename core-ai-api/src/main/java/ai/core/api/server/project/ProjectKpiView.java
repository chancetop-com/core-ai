package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectKpiView {
    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "key")
    public String key;

    @Property(name = "value")
    public String value;

    @Property(name = "unit")
    public String unit;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "created_by")
    public String createdBy;
}
