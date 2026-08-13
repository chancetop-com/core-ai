package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TraceFacetView {
    @Property(name = "value")
    public String value;

    @Property(name = "count")
    public Integer count;
}
