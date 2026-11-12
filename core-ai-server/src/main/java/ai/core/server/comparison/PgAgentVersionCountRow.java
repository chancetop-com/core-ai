package ai.core.server.comparison;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAgentVersionCountRow {
    @Column(name = "total")
    public Long total;
}
