package ai.core.api.server.tool;

import core.framework.api.json.Property;

/**
 * API view for a structured tool reference, replacing plain toolId strings.
 * Contains the tool identifier, its source type, and an optional source name.
 *
 * @author stephen
 */
public class ToolRefView {
    @Property(name = "id")
    public String id;

    @Property(name = "type")
    public String type;

    /** Source identifier for disambiguation. E.g., MCP server name, API app name. */
    @Property(name = "source")
    public String source;
}