package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ComparisonSessionPairView {
    @Property(name = "pair_id")
    public String pairId;

    @Property(name = "session_a")
    public String sessionA;

    @Property(name = "session_b")
    public String sessionB;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "turn_count")
    public Integer turnCount;

    @Property(name = "vote_count")
    public Integer voteCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
