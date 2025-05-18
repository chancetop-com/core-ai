package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class ServerCapabilities {
    @Property(name = "experimental")
    public Map<String, String> experimental;

    @Property(name = "logging")
    public LoggingCapabilities logging;

    @Property(name = "prompts")
    public PromptCapabilities prompts;

    @Property(name = "resources")
    public ResourceCapabilities resources;

    @Property(name = "tools")
    public ToolCapabilities tools;
}
