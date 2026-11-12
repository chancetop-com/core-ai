package ai.core.server.run;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentRunCountRow {
    @Column(name = "total")
    public Long total;
}
