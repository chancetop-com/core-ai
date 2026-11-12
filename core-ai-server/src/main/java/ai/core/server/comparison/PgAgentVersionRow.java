package ai.core.server.comparison;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentVersionRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
