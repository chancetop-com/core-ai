package ai.core.api.server.conversation;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author core-ai
 */
public class ConversationCreateSessionRequest {
    @NotNull
    @NotBlank
    @Property(name = "agent_id")
    public String agentId;

    /** Client-generated idempotency key; resending it must return the same session. */
    @NotNull
    @NotBlank
    @Property(name = "client_request_id")
    public String clientRequestId;
}
