package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

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

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
