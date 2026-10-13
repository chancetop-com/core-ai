package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * One turn's vote. "chosen" is one of A / B / TIE / NEITHER; skipping a turn sends no vote at all.
 *
 * @author stephen
 */
public class SubmitComparisonVoteRequest {
    @Property(name = "session_a")
    public String sessionA;

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
}
