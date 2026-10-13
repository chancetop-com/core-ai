package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ComparisonVoteView {
    @Property(name = "id")
    public String id;

    @Property(name = "comparison_id")
    public String comparisonId;

    @Property(name = "pair_id")
    public String pairId;

    @Property(name = "session_a")
    public String sessionA;

    @Property(name = "session_b")
    public String sessionB;

    @Property(name = "turn")
    public Long turn;

    @Property(name = "chosen")
    public String chosen;

    @Property(name = "comment")
    public String comment;

    @Property(name = "message_a")
    public String messageA;

    @Property(name = "message_b")
    public String messageB;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
