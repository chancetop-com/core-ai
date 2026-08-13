package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListTraceFacetsResponse {
    @Property(name = "facets")
    public List<TraceFacetView> facets;
}
