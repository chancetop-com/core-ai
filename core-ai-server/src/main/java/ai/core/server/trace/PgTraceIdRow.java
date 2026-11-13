package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceIdRow {
    @Column(name = "id")
    public String id;
}
