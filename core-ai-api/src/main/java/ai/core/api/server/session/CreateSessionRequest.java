package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateSessionRequest {
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "config")
    public SessionConfig config;
}
