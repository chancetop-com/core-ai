package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

/**
 * One input attachment of a hub run, forwarded to the agent as an A2A file part. The optional storage
 * coordinates make a platform-owned file behave like a chat attachment — staged into the session's
 * sandbox and, for images, attached to the model context; a URL without them is handed to the agent as-is.
 *
 * @author stephen
 */
public class AgentHubAttachment {
    @Property(name = "url")
    public String url;

    @Property(name = "type")
    public String type;

    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "container")
    public String container;

    @Property(name = "blob_name")
    public String blobName;
}
