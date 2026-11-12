package ai.core.server.comparison;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgComparisonVoteRow {
    @Column(name = "id")
    public String id;

    @Column(name = "comparison_id")
    public String comparisonId;

    @Column(name = "pair_id")
    public String pairId;

    @Column(name = "session_a")
    public String sessionA;

    @Column(name = "session_b")
    public String sessionB;

    @Column(name = "turn")
    public Long turn;

    @Column(name = "chosen")
    public String chosen;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "data")
    public String data;
}
