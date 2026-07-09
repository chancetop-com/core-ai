package ai.core.api.server.notification;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * SSE event payload pushed to connected notification clients.
 * Mirrors {@link NotificationView} fields so the frontend receives the same
 * schema for both polling and push delivery.
 *
 * @author stephen
 */
public class NotificationSseEvent {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "category")
    public String category;

    @NotNull
    @Property(name = "type")
    public String type;

    @NotNull
    @Property(name = "title")
    public String title;

    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "session_id")
    public String sessionId;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
