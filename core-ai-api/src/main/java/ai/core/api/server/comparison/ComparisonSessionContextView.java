package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Comparison metadata resolved from one session id of a pair; comparison_id is null for a regular
 * session, which tells the client to stay on the normal chat path.
 *
 * @author stephen
 */
public class ComparisonSessionContextView {
    @Property(name = "comparison_id")
    public String comparisonId;

    @Property(name = "pair_id")
    public String pairId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "status")
    public String status;

    /** this session's side: "a" (displayed as 版本 1) or "b" (version 2) */
    @Property(name = "variant")
    public String variant;

    @Property(name = "primary_session_id")
    public String primarySessionId;

    @Property(name = "secondary_session_id")
    public String secondarySessionId;

    @Property(name = "blind")
    public Boolean blind;

    @Property(name = "budget_usd")
    public Double budgetUsd;

    @Property(name = "votes")
    public List<ComparisonVoteView> votes;
}
