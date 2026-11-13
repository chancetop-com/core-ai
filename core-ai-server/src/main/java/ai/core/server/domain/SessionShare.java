package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Holds one share generation per session, using the session as Mongo's immutable key.
 *
 * @author Xander
 */
@Collection(name = "session_shares")
public class SessionShare {
    @NotNull
    @Field(name = "share_id")
    public String id;

    @Id
    public String sessionId;

    @NotNull
    @Field(name = "owner_user_id")
    public String ownerUserId;

    @NotNull
    @Field(name = "token")
    public String token;

    @Field(name = "title")
    public String title;

    @NotNull
    @Field(name = "max_message_seq")
    public Long maxMessageSeq;

    @NotNull
    @Field(name = "message_count")
    public Long messageCount;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Field(name = "revoked_at")
    public ZonedDateTime revokedAt;

    @NotNull
    @Field(name = "trace_ids")
    public List<String> traceIds;

    @NotNull
    @Field(name = "files")
    public List<SessionShareFile> files;
}
