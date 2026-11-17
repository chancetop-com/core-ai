package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationCreateSessionResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "model")
    public String model;

    @Property(name = "created")
    public Boolean created;

    @Property(name = "request_status")
    public String requestStatus;
}
