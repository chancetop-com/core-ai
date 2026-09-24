package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * The last platform-side address a user talked from on a channel — for QQ that is the
 * {@code qqbot:c2c:<openid>} the inbound message carried. It is what makes a proactive send
 * targetable without asking the user to know their own platform id.
 *
 * @author stephen
 */
@Collection(name = "user_channel_targets")
public class UserChannelTarget {
    public static String idOf(String userId, String channelId) {
        return userId + "|" + channelId;
    }

    /** deterministic: one row per (user, channel), replaced in place */
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "channel_id")
    public String channelId;

    @NotNull
    @Field(name = "recipient")
    public String recipient;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
