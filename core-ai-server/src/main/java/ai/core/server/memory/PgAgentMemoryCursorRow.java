package ai.core.server.memory;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentMemoryCursorRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "data")
    public String data;
}
