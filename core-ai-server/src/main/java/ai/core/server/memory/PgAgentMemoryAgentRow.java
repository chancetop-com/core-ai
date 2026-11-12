package ai.core.server.memory;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentMemoryAgentRow {
    @Column(name = "agent_id")
    public String agentId;
}
