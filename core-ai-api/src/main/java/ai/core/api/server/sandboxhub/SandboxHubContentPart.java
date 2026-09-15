package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * One item of a tool result's content array (MCP {@code CallToolResult} shapes it; API/LLM_CALL
 * results always carry a single text part). {@code data} is base64 text for image/embedded
 * content — reserved for later iterations.
 *
 * @author stephen
 */
public class SandboxHubContentPart {
    @Property(name = "type")
    public String type;

    @Property(name = "text")
    public String text;

    @Property(name = "mime_type")
    public String mimeType;

    @Property(name = "data")
    public String data;
}
