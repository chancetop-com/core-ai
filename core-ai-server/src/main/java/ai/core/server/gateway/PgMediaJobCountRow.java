package ai.core.server.gateway;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgMediaJobCountRow {
    @Column(name = "total")
    public Long total;
}
