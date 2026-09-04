package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * One item of an MCP {@code CallToolResult} content array.
 * <p>
 * P0 always carries a single text part; {@code data}/{@code mimeType} are reserved for
 * image/embedded content in later iterations (base64 text).
 *
 * @author stephen
 */
public class HubContentPart {
    @Property(name = "type")
    public String type;

    @Property(name = "text")
    public String text;

    @Property(name = "data")
    public String data;

    @Property(name = "mime_type")
    public String mimeType;
}
