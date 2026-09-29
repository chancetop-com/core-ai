package ai.core.api.server.hubcatalog;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Everything the caller can reach, in the shape a client builds a tool catalog from.
 *
 * @author stephen
 */
public class HubCatalogResponse {
    @Property(name = "generated_at")
    public String generatedAt;

    /** The MCP servers and API apps behind the tools: display metadata and staleness. */
    @Property(name = "sources")
    public List<HubCatalogSource> sources;

    /**
     * The kinds this response covers ({@code mcp}, {@code api}, {@code agent}, {@code llm_call}).
     * A kind missing here is one the caller may not read, which a client shows as a gap rather than
     * mistaking it for a kind with nothing in it.
     */
    @Property(name = "sections")
    public List<String> sections;

    /** Flat and sorted by kind then path; {@code kind} is {@code mcp}, {@code api}, {@code agent} or {@code llm_call}. */
    @Property(name = "tools")
    public List<HubCatalogTool> tools;
}
