package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * A matched MCP server inside a search response. Carries the number of matched
 * tools ({@code matched_count}) so clients can render "{@code (+N more, --on-server ...)}"
 * and the server-level score ({@code score}, brand layer) that ranks the result.
 *
 * @author stephen
 */
public class HubServerMatch {
    @Property(name = "name")
    public String name;

    @Property(name = "matched_count")
    public Integer matchedCount;

    @Property(name = "score")
    public Integer score;

    @Property(name = "state")
    public String state;

    @Property(name = "stale")
    public Boolean stale;
}
