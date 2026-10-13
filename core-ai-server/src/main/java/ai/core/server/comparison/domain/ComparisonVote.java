package ai.core.server.comparison.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One tester's vote on one turn of a session pair; upserted by (comparison_id, session_a, turn).
 *
 * @author stephen
 */
@Collection(name = "comparison_votes")
public class ComparisonVote {
    @Id
    public String id;

    @Field(name = "comparison_id")
    public String comparisonId;

    @Field(name = "pair_id")
    public String pairId;

    @Field(name = "session_a")
    public String sessionA;

    @Field(name = "session_b")
    public String sessionB;

    /** 1-based ordinal of the user turn within the pair. */
    @Field(name = "turn")
    public Long turn;

    @Field(name = "chosen")
    public ComparisonVoteChoice chosen;

    @Field(name = "comment")
    public String comment;

    @Field(name = "message_a")
    public String messageA;

    @Field(name = "message_b")
    public String messageB;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
