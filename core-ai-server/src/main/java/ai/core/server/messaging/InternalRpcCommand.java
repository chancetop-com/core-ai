package ai.core.server.messaging;

/**
 * Wire format of {@code POST /_internal/rpc}; shared by the client and the receiver.
 *
 * @author stephen
 */
public class InternalRpcCommand {
    public String type;
    public String sessionId;
    public String userId;
    public String payload;
    public String requestId;

    public InternalRpcCommand() {
    }

    public InternalRpcCommand(String type, String sessionId, String userId, String payload, String requestId) {
        this.type = type;
        this.sessionId = sessionId;
        this.userId = userId;
        this.payload = payload;
        this.requestId = requestId;
    }
}
