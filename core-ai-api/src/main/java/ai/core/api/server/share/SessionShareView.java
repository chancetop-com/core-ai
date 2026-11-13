package ai.core.api.server.share;

import core.framework.api.json.Property;
import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class SessionShareView {
    @Property(name = "id")
    public String id;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "title")
    public String title;

    @Property(name = "url")
    public String url;

    @Property(name = "max_message_seq")
    public Long maxMessageSeq;

    @Property(name = "message_count")
    public Long messageCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Property(name = "revoked_at")
    public ZonedDateTime revokedAt;
}
