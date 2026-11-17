package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationSendResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "client_request_id")
    public String clientRequestId;

    @Property(name = "request_status")
    public String requestStatus;

    @Property(name = "duplicate")
    public Boolean duplicate;
}
