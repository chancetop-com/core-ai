package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ChatSessionSummaryView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "source")
    public String source;

    @Property(name = "schedule_id")
    public String scheduleId;

    @Property(name = "api_key_id")
    public String apiKeyId;

    @Property(name = "title")
    public String title;

    @Property(name = "message_count")
    public Long messageCount;

    @Property(name = "created_at")
    public String createdAt;

    @Property(name = "last_message_at")
    public String lastMessageAt;
}
