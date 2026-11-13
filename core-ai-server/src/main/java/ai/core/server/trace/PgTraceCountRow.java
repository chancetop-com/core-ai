package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceCountRow {
    @Column(name = "count")
    public Long count;
}
