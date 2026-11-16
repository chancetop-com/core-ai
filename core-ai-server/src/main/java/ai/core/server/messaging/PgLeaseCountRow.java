package ai.core.server.messaging;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgLeaseCountRow {
    @Column(name = "count")
    public Long count;
}
