package ai.core.api.server.conversation;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author core-ai
 */
public class ConversationSendRequest {
    @NotNull
    @NotBlank
    @Property(name = "message")
    public String message;

    /** Client-generated idempotency key; resending it must not start a second turn. */
    @NotNull
    @NotBlank
    @Property(name = "client_request_id")
    public String clientRequestId;
}
