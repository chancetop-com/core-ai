package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Search response with two levels: {@code servers} lists every server that has
 * matched tools (with matched counts and server-level scores) and {@code tools}
 * carries the diversified top picks (at most 3 per server) ordered by server
 * score round-robin. A query-less listing returns flat tools and no servers.
 *
 * @author stephen
 */
public class HubToolsResponse {
    @Property(name = "servers")
    public List<HubServerMatch> servers;

    @Property(name = "tools")
    public List<HubToolSummary> tools;
}
