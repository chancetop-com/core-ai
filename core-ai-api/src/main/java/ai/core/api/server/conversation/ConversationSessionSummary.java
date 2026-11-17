package ai.core.api.server.conversation;

import core.framework.api.json.Property;

import java.time.Instant;

/**
 * @author core-ai
 */
public class ConversationSessionSummary {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "title")
    public String title;

    @Property(name = "source")
    public String source;

    @Property(name = "message_count")
    public Long messageCount;

    @Property(name = "created_at")
    public Instant createdAt;

    @Property(name = "last_message_at")
    public Instant lastMessageAt;
}
