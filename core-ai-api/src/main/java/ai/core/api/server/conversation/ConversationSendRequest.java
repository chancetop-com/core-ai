package ai.core.api.server.conversation;

import ai.core.api.server.session.SendMessageRequest;
import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author core-ai
 */
public class ConversationSendRequest {
    /** Empty is allowed when attachments carry the message; the server rejects a send with neither. */
    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "attachments")
    public List<SendMessageRequest.SendMessageAttachment> attachments;

    /** Client-generated idempotency key; resending it must not start a second turn. */
    @NotNull
    @NotBlank
    @Property(name = "client_request_id")
    public String clientRequestId;
}
