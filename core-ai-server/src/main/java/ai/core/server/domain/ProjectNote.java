package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Embedded note/decision; empty subject_id means project-level.
 *
 * @author stephen
 */
public class ProjectNote {
    @Field(name = "subject_id")
    public String subjectId;

    @Field(name = "content")
    public String content;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "created_by")
    public String createdBy;
}
