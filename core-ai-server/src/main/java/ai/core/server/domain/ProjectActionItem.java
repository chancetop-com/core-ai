package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Embedded action item; empty subject_id means project-level.
 *
 * @author stephen
 */
public class ProjectActionItem {
    @Field(name = "subject_id")
    public String subjectId;

    @Field(name = "id")
    public String id;   // UUID generated on create

    @Field(name = "title")
    public String title;

    @Field(name = "status")
    public String status;   // open | in_progress | done, validated in service

    @Field(name = "note")
    public String note;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "updated_by")
    public String updatedBy;
}
