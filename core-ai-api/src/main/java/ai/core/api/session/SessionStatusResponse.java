package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.Instant;

/**
 * @author stephen
 */
public class SessionStatusResponse {
    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "status")
    public SessionStatus status;

    @Property(name = "createdAt")
    public Instant createdAt;

    @Property(name = "lastActiveAt")
    public Instant lastActiveAt;

    @Property(name = "messageCount")
    public Integer messageCount;

    @Property(name = "estimatedTokens")
    public Long estimatedTokens;
}
