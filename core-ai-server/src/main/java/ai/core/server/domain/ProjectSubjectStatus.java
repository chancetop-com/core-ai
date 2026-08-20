package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Subject-level status row (overwrite semantics); project-level status stays in Project.status_*.
 *
 * @author stephen
 */
public class ProjectSubjectStatus {
    @Field(name = "subject_id")
    public String subjectId;

    @Field(name = "phase")
    public String phase;

    @Field(name = "summary")
    public String summary;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "updated_by")
    public String updatedBy;   // agentId or userId
}
