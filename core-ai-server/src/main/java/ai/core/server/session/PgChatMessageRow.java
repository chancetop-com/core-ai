package ai.core.server.session;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgChatMessageRow {
    @Column(name = "id")
    public String id;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "seq")
    public Long seq;

    @Column(name = "role")
    public String role;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "content")
    public String content;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "data")
    public String data;
}
