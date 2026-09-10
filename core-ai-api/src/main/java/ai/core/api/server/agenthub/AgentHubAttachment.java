package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

/**
 * One input attachment of a hub run. P0 accepts public URLs only; the server forwards them
 * as A2A file parts.
 *
 * @author stephen
 */
public class AgentHubAttachment {
    @Property(name = "url")
    public String url;

    @Property(name = "type")
    public String type;
}
