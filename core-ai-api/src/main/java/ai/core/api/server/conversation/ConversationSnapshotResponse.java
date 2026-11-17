package ai.core.api.server.conversation;

import core.framework.api.json.Property;

import java.time.Instant;

/**
 * Authoritative state of one conversation, read without rebuilding the agent.
 *
 * @author core-ai
 */
public class ConversationSnapshotResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "title")
    public String title;

    @Property(name = "status")
    public String status;

    @Property(name = "message_count")
    public Long messageCount;

    @Property(name = "last_seq")
    public Long lastSeq;

    @Property(name = "created_at")
    public Instant createdAt;

    @Property(name = "last_active_at")
    public Instant lastActiveAt;
}
