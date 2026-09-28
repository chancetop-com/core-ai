package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * A notice the platform pushed to a user on one of their channels, kept so the conversation it points
 * at can still be found afterwards. The row is what makes a reply answerable: the notice itself is
 * already out of the platform's hands (it lives in the user's QQ/WeChat), and nothing else records
 * that the receiving side ever saw it.
 *
 * <p>"Answered" is not a judgement about the reply: any inbound message from the same channel and
 * address counts, so no model has to tell a real answer from an unrelated question.
 *
 * @author stephen
 */
@Collection(name = "session_notices")
public class SessionNotice {
    /** First kind of notice there is; scheduled-task results and cost alerts can reuse the collection. */
    public static final String KIND_SESSION_COMPLETION = "session_completion";

    @Id
    public String id;

    /** The session's owner — the only user who may be told about it. */
    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "channel_id")
    public String channelId;

    /** Platform-side address the notice went to ({@code qqbot:c2c:<openid>}). */
    @NotNull
    @Field(name = "recipient")
    public String recipient;

    @NotNull
    @Field(name = "kind")
    public String kind;

    /** The conversation the notice is about, as {@code read_session} addresses it. */
    @NotNull
    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "title")
    public String title;

    /** What the user was told, short enough to quote back into a prompt. */
    @Field(name = "summary")
    public String summary;

    @NotNull
    @Field(name = "answered")
    public Boolean answered = Boolean.FALSE;

    @Field(name = "answered_at")
    public ZonedDateTime answeredAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
