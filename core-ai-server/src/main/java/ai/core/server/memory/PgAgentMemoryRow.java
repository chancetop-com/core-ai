package ai.core.server.memory;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentMemoryRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "type")
    public String type;

    @Column(name = "layer")
    public String layer;

    @Column(name = "content")
    public String content;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
