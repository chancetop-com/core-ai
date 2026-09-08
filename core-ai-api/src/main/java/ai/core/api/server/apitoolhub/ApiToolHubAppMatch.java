package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * Brand-layer search hit: an app with at least one matched operation.
 * {@code score > 0} means the app name itself matched the query.
 *
 * @author stephen
 */
public class ApiToolHubAppMatch {
    @Property(name = "name")
    public String name;

    @Property(name = "matched_count")
    public Integer matchedCount;

    @Property(name = "score")
    public Integer score;
}
