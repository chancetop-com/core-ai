package ai.core.server.comparison;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentComparisonRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "status")
    public String status;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "ended_at")
    public ZonedDateTime endedAt;

    @Column(name = "data")
    public String data;
}
