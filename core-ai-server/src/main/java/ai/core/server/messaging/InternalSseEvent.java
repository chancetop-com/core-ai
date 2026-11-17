package ai.core.server.messaging;

/**
 * Wire format of the low-frequency {@code POST /_internal/sessions/events} forwarding path.
 *
 * @author stephen
 */
public class InternalSseEvent {
    public String sessionId;
    public String eventJson;

    public InternalSseEvent() {
    }

    public InternalSseEvent(String sessionId, String eventJson) {
        this.sessionId = sessionId;
        this.eventJson = eventJson;
    }
}
