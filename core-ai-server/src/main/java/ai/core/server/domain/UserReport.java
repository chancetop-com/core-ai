package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

@Collection(name = "user_reports")
public class UserReport {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "title")
    public String title;

    @Field(name = "content")
    public String content;

    @Field(name = "type")
    public String type;

    @Field(name = "tags")
    public List<String> tags;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
