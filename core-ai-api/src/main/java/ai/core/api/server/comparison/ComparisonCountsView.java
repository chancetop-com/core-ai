package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ComparisonCountsView {
    @Property(name = "votes_a")
    public Integer votesA;

    @Property(name = "votes_b")
    public Integer votesB;

    @Property(name = "ties")
    public Integer ties;

    @Property(name = "neithers")
    public Integer neithers;

    @Property(name = "votes")
    public Integer votes;

    /** user turns across all session pairs; votes below this number = skipped turns */
    @Property(name = "turns")
    public Integer turns;
}
