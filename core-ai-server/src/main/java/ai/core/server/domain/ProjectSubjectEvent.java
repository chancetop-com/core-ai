package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Append-only event row of the authoritative subject history (product decision D7). The embedded
 * arrays on the project document stay the "current state" read surface; this collection feeds the
 * timeline, trends and the HTML campaign report renderer.
 *
 * @author stephen
 */
@Collection(name = "project_subject_events")
public class ProjectSubjectEvent {
    public static final String TYPE_PHASE = "phase";
    public static final String TYPE_SUMMARY = "summary";
    public static final String TYPE_KPI = "kpi";
    public static final String TYPE_ACTION_ITEM = "action_item";
    public static final String TYPE_NOTE = "note";
    public static final String TYPE_SUBJECT_STATUS = "subject_status";

    @Id
    public String id;

    @Field(name = "project_id")
    public String projectId;

    @Field(name = "subject_id")
    public String subjectId;

    // phase | summary | kpi | action_item | note | subject_status
    @Field(name = "type")
    public String type;

    // grouping key: phase name / kpi key / action item id
    @Field(name = "key")
    public String key;

    // the new value at this point: new phase / kpi value / new status / content
    @Field(name = "value")
    public String value;

    // optional JSON context: {unit} / {previous_phase} / {title}
    @Field(name = "meta")
    public String meta;

    @Field(name = "at")
    public ZonedDateTime at;   // event time = write time

    @Field(name = "created_by")
    public String createdBy;   // "project-agent" or the acting userId
}
