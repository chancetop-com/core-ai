package ai.core.server.session;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgChatSessionRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "source")
    public String source;

    @Column(name = "comparison_id")
    public String comparisonId;

    @Column(name = "comparison_pair_id")
    public String comparisonPairId;

    @Column(name = "comparison_variant")
    public String comparisonVariant;

    @Column(name = "title")
    public String title;

    @Column(name = "message_count")
    public Long messageCount;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "last_message_at")
    public ZonedDateTime lastMessageAt;

    @Column(name = "notify_on_complete")
    public Boolean notifyOnComplete;

    @Column(name = "deleted_at")
    public ZonedDateTime deletedAt;

    @Column(name = "data")
    public String data;
}
