package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

@Collection(name = "user_todos")
public class UserTodo {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "title")
    public String title;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "completed")
    public Boolean completed = Boolean.FALSE;

    @Field(name = "priority")
    public String priority;

    @Field(name = "due_date")
    public ZonedDateTime dueDate;

    /** MANUAL or AUTO; documents written before the field existed read as MANUAL. */
    @NotNull
    @Field(name = "source")
    public String source = "MANUAL";

    /** AUTO only: the local date (Asia/Shanghai) whose sessions were summarized. */
    @Field(name = "origin_date")
    public String originDate;

    /** AUTO only: the sessions that produced this todo. */
    @Field(name = "origin_session_ids")
    public List<String> originSessionIds;

    /** AUTO only: deterministic dedupe key, one per user/day/session/title. */
    @Field(name = "dedupe_key")
    public String dedupeKey;

    /** AUTO only: the model that summarized the sessions. */
    @Field(name = "generated_by")
    public String generatedBy;

    @Field(name = "generated_at")
    public ZonedDateTime generatedAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
