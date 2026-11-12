package ai.core.server.notification;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgNotificationRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "category")
    public String category;

    @Column(name = "type")
    public String type;

    @Column(name = "title")
    public String title;

    @Column(name = "message")
    public String message;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "status")
    public String status;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "read_at")
    public ZonedDateTime readAt;

    @Column(name = "data")
    public String data;
}
