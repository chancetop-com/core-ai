package ai.core.api.server.share;

import core.framework.api.json.Property;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
public class SharedSessionView {
    @Property(name = "title")
    public String title;

    @Property(name = "max_message_seq")
    public Long maxMessageSeq;

    @Property(name = "message_count")
    public Long messageCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Property(name = "artifacts")
    public List<SharedSessionArtifact> artifacts;
}
