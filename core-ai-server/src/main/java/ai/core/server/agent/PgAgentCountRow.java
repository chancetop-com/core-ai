package ai.core.server.agent;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentCountRow {
    @Column(name = "total")
    public Long total;
}
