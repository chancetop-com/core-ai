package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
@Collection(name = "chat_sessions")
public class ChatSession {
    // same value as in-memory session id
    @Id
    public String id;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "agent_id")
    public String agentId;

    // derived from first user message, truncated; editable later
    @Field(name = "title")
    public String title;

    @Field(name = "message_count")
    public Long messageCount;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "last_message_at")
    public ZonedDateTime lastMessageAt;

    @Field(name = "deleted_at")
    public ZonedDateTime deletedAt;
}
