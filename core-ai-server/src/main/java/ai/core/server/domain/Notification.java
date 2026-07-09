package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "notifications")
public class Notification {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "category")
    public NotificationCategory category;

    @NotNull
    @Field(name = "type")
    public NotificationType type;

    @NotNull
    @Field(name = "title")
    public String title;

    @NotNull
    @Field(name = "message")
    public String message;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "session_id")
    public String sessionId;

    @NotNull
    @Field(name = "status")
    public NotificationStatus status;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "read_at")
    public ZonedDateTime readAt;
}
