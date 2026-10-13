package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateComparisonSessionsResponse {
    @Property(name = "comparison_id")
    public String comparisonId;

    @Property(name = "pair_id")
    public String pairId;

    @Property(name = "primary_session_id")
    public String primarySessionId;

    @Property(name = "secondary_session_id")
    public String secondarySessionId;
}
