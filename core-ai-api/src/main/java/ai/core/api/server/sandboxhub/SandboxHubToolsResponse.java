package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Flat tool search result ({@code GET /api/sandbox-hub/tools?query=&kind=&limit=}).
 *
 * @author stephen
 */
public class SandboxHubToolsResponse {
    @Property(name = "query")
    public String query;

    @Property(name = "kind")
    public String kind;

    @Property(name = "total")
    public Integer total;

    @Property(name = "tools")
    public List<SandboxHubToolSummary> tools;
}
