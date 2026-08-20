package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * One append-only subject history row (product decision D7): the event series behind the
 * timeline, trends and the HTML campaign report.
 *
 * @author stephen
 */
public class ProjectEventView {
    @Property(name = "id")
    public String id;

    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "type")
    public String type;   // phase | summary | kpi | action_item | note | subject_status

    @Property(name = "key")
    public String key;    // phase name / kpi key / action item id

    @Property(name = "value")
    public String value;

    @Property(name = "meta")
    public String meta;

    @Property(name = "at")
    public ZonedDateTime at;

    @Property(name = "created_by")
    public String createdBy;
}
