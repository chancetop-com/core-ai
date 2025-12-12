package ai.core.jsonschema;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.Instant;

/**
 * Base Event Model for SSE Streaming
 * <p>
 * All SSE events extend from this base class providing common fields.
 *
 * @author cyril
 */
public abstract class BaseEvent {
    /**
     * Event type (required)
     */
    @NotNull
    @Property(name = "type")
    public EventType type;

    /**
     * ISO 8601 timestamp (auto-generated)
     */
    @NotNull
    @Property(name = "timestamp")
    public String timestamp;

    /**
     * Conversation identifier (optional)
     */
    @Property(name = "conversation_id")
    public String conversationId;

    /**
     * Protected constructor for subclasses
     *
     * @param type Event type
     */
    protected BaseEvent(EventType type) {
        this.type = type;
        this.timestamp = Instant.now().toString();
    }

    /**
     * No-arg constructor for JSON deserialization
     */
    protected BaseEvent() {
    }
}
