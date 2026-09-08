package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Two-level search result: {@code apps} lists every app with matched operations
 * (brand matches first, matched counts attached) and {@code operations} carries the
 * diversified picks — at most 3 per app — ordered by app score round-robin.
 * A query-less listing returns flat operations and no apps.
 *
 * @author stephen
 */
public class ApiToolHubSearchResponse {
    @Property(name = "apps")
    public List<ApiToolHubAppMatch> apps;

    @Property(name = "operations")
    public List<ApiToolHubOperationSummary> operations;
}
