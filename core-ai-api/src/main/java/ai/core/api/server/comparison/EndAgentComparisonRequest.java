package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class EndAgentComparisonRequest {
    /** "a" / "b" to adopt that variant, null to abandon without adopting. */
    @Property(name = "adopt")
    public String adopt;

    /** Required when the live agent changed during the test and adopt would overwrite newer edits. */
    @Property(name = "force")
    public Boolean force;
}
